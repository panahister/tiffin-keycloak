package io.portable.identity.events;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import java.util.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

final class OutboxPublisher implements Runnable, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class.getName());
    private static final Path UNREADY = Path.of("/tmp/keycloak-identity-events-unready");
    private final ProviderConfig config;
    private final KeycloakSessionFactory sessionFactory;
    private final OutboxRepository repository;
    private final EventMetrics metrics;
    private KafkaProducer<String, String> producer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    OutboxPublisher(ProviderConfig config, KeycloakSessionFactory sessionFactory,
                    OutboxRepository repository, EventMetrics metrics) {
        this.config = config;
        this.sessionFactory = sessionFactory;
        this.repository = repository;
        this.metrics = metrics;
        this.worker = new Thread(this, "identity-event-outbox-publisher");
        this.worker.setDaemon(true);
    }

    void start() { worker.start(); }

    @Override
    public void run() {
        while (running.get()) {
            try {
                long claimStarted = System.nanoTime();
                List<OutboxRecord> records = transaction(entityManager -> repository.claim(entityManager, config));
                metrics.databaseClaimLatency(System.nanoTime() - claimStarted);
                for (OutboxRecord record : records) {
                    boolean renewed = transaction(entityManager ->
                        repository.renew(entityManager, record, config.claimSeconds));
                    if (renewed) publish(record);
                    else metrics.lostClaim("renew");
                }
                updateHealth();
                transaction(entityManager -> { repository.cleanup(entityManager, config.retentionSeconds); return null; });
                if (records.isEmpty()) sleep(config.pollIntervalMs);
            } catch (Throwable failure) {
                LOG.severe("identity-event publisher cycle failed class=" + safeClass(failure));
                markUnready("publisher-cycle-failure");
                sleep(config.pollIntervalMs);
            }
        }
    }

    private void publish(OutboxRecord record) {
        long publishStarted = System.nanoTime();
        try {
            ProducerRecord<String, String> kafkaRecord = new ProducerRecord<>(record.topic(), record.partitionKey(), record.payload());
            kafkaRecord.headers().add(new RecordHeader("ce_id", record.eventId().getBytes(StandardCharsets.UTF_8)));
            kafkaRecord.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
            kafkaRecord.headers().add(new RecordHeader("schema-version", record.schemaVersion().getBytes(StandardCharsets.UTF_8)));
            kafkaRecord.headers().add(new RecordHeader("publish-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));
            producer().send(kafkaRecord).get(config.deliveryTimeoutMs, TimeUnit.MILLISECONDS);
            boolean acknowledged = transaction(entityManager -> repository.acknowledge(entityManager, record));
            if (acknowledged) {
                metrics.published(record.kind());
                metrics.publishLatency(System.nanoTime() - publishStarted);
            }
            else metrics.lostClaim("acknowledge");
        } catch (Throwable failure) {
            String errorClass = safeClass(failure);
            int nextAttempt = record.attemptCount() + 1;
            if (nextAttempt >= config.maxAttempts && publishDeadLetter(record, errorClass, nextAttempt)) {
                boolean deadLettered = transaction(entityManager -> repository.deadLetter(entityManager, record, errorClass));
                if (deadLettered) metrics.deadLettered();
                else metrics.lostClaim("dead-letter");
            } else {
                long backoff = Math.min(60000L, (long) config.baseBackoffMs << Math.min(16, record.attemptCount()));
                boolean retried = transaction(entityManager -> repository.retry(entityManager, record, errorClass, backoff));
                if (retried) metrics.retried(errorClass);
                else metrics.lostClaim("retry");
            }
        }
    }

    private boolean publishDeadLetter(OutboxRecord record, String errorClass, int attempts) {
        try {
            boolean renewed = transaction(entityManager ->
                repository.renew(entityManager, record, config.claimSeconds));
            if (!renewed) {
                metrics.lostClaim("dead-letter-renew");
                return false;
            }
            String now = Instant.now().toString();
            String payload = Json.encode(java.util.Map.of(
                "specversion", "1.0",
                "id", "dead-letter-" + record.outboxId(),
                "source", config.source,
                "type", "identity.dead-letter.v1",
                "time", now,
                "datacontenttype", "application/json",
                "dataschema", "urn:portable:keycloak:identity:dead-letter:v1",
                "subject", record.eventId(),
                "data", java.util.Map.of(
                    "schemaVersion", "1.0",
                    "originalEventId", record.eventId(),
                    "destinationTopic", record.topic(),
                    "originalSchemaVersion", record.schemaVersion(),
                    "failureClassification", "delivery-exhausted",
                    "attemptCount", attempts,
                    "sanitizedErrorClass", errorClass
                )
            ));
            producer().send(new ProducerRecord<>(config.deadLetterTopic, record.partitionKey(), payload))
                .get(config.deliveryTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable failure) {
            LOG.warning("identity-event dead-letter publish failed eventId=" + record.eventId() + " class=" + safeClass(failure));
            return false;
        }
    }

    private void updateHealth() {
        long[] backlog = transaction(entityManager -> new long[] {
            repository.pendingCount(entityManager), repository.oldestPendingAgeSeconds(entityManager)
        });
        metrics.updateBacklog(backlog[0], backlog[1]);
        if (backlog[0] >= config.backlogMaxRows || backlog[1] >= config.backlogMaxAgeSeconds) {
            markUnready("outbox-threshold-exceeded");
        } else {
            try { Files.deleteIfExists(UNREADY); } catch (Exception ignored) { }
        }
    }

    private void markUnready(String reason) {
        try { Files.writeString(UNREADY, reason + "\n", StandardCharsets.US_ASCII); }
        catch (Exception failure) { LOG.severe("cannot write identity-event readiness marker"); }
    }

    private <T> T transaction(Function<EntityManager, T> work) {
        KeycloakSession session = sessionFactory.create();
        try {
            session.getTransactionManager().begin();
            EntityManager entityManager = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            T result = work.apply(entityManager);
            session.getTransactionManager().commit();
            return result;
        } catch (Throwable failure) {
            if (session.getTransactionManager().isActive()) session.getTransactionManager().rollback();
            throw failure;
        } finally {
            session.close();
        }
    }

    static String safeClass(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current.getClass().getName().contains("CompletionException"))
            && current.getCause() != null) current = current.getCause();
        String name = current.getClass().getSimpleName();
        return name.isBlank() ? "DeliveryFailure" : name.substring(0, Math.min(96, name.length()));
    }

    private static void sleep(long milliseconds) {
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private KafkaProducer<String, String> producer() {
        if (producer == null) producer = new KafkaProducer<>(config.producerProperties);
        return producer;
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
        try { worker.join(Duration.ofSeconds(10).toMillis()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (producer != null) producer.close(Duration.ofSeconds(10));
    }
}
