package io.portable.identity.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

final class EventMetrics {
    private final AtomicLong backlogDepth = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong previousBacklogDepth = new AtomicLong();

    EventMetrics() {
        Gauge.builder("keycloak_identity_event_outbox_depth", backlogDepth, AtomicLong::get)
            .description("Committed identity events waiting for Kafka acknowledgement")
            .register(Metrics.globalRegistry);
        Gauge.builder("keycloak_identity_event_outbox_oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
            .description("Age of the oldest committed unpublished identity event")
            .register(Metrics.globalRegistry);
        Gauge.builder("keycloak_identity_event_last_publish_epoch_seconds", lastSuccessEpochSeconds, AtomicLong::get)
            .description("Epoch second of the last acknowledged Kafka publish")
            .register(Metrics.globalRegistry);
    }

    void captured(String kind) { Metrics.counter("keycloak_identity_events_captured_total", "kind", kind).increment(); }
    void published(String kind) {
        Metrics.counter("keycloak_identity_events_published_total", "kind", kind).increment();
        lastSuccessEpochSeconds.set(System.currentTimeMillis() / 1000);
    }
    void retried(String failure) { Metrics.counter("keycloak_identity_event_retries_total", "failure", failure).increment(); }
    void deadLettered() { Metrics.counter("keycloak_identity_event_dead_letter_total").increment(); }
    void lostClaim(String operation) {
        Metrics.counter("keycloak_identity_event_lost_claim_total", "operation", operation).increment();
    }
    void captureFailed() { Metrics.counter("keycloak_identity_event_capture_failures_total").increment(); }
    void publishLatency(long nanoseconds) {
        Metrics.timer("keycloak_identity_event_publish_latency_seconds")
            .record(nanoseconds, TimeUnit.NANOSECONDS);
    }
    void databaseClaimLatency(long nanoseconds) {
        Metrics.timer("keycloak_identity_event_database_claim_latency_seconds")
            .record(nanoseconds, TimeUnit.NANOSECONDS);
    }
    void updateBacklog(long depth, long age) {
        long previous = previousBacklogDepth.getAndSet(depth);
        if (previous > 0 && depth == 0) {
            Metrics.counter("keycloak_identity_event_backlog_recoveries_total").increment();
        }
        backlogDepth.set(depth);
        oldestAgeSeconds.set(age);
    }
}
