package io.portable.identity.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class ProviderConfig {
    final boolean enabled;
    final boolean required;
    final String runtimeMode;
    final String bootstrapServers;
    final String clientId;
    final String source;
    final String userTopic;
    final String adminTopic;
    final String securityTopic;
    final String deadLetterTopic;
    final String privacyIpMode;
    final byte[] ipHmacKey;
    final int pollIntervalMs;
    final int claimBatchSize;
    final int claimSeconds;
    final int claimSafetyMarginMs;
    final int maxAttempts;
    final int baseBackoffMs;
    final int deliveryTimeoutMs;
    final int requestTimeoutMs;
    final int backlogMaxRows;
    final int backlogMaxAgeSeconds;
    final int retentionSeconds;
    final int expectedPartitions;
    final int expectedReplicationFactor;
    final String workerId;
    final Properties producerProperties;

    private ProviderConfig(Map<String, String> env) {
        enabled = bool(env, "KAFKA_EVENT_EXPORT_ENABLED", false);
        required = bool(env, "KAFKA_EVENT_EXPORT_REQUIRED", false);
        runtimeMode = value(env, "KAFKA_RUNTIME_MODE", "disabled");
        bootstrapServers = value(env, "KAFKA_BOOTSTRAP_SERVERS", "");
        clientId = value(env, "KAFKA_CLIENT_ID", "keycloak-identity-events");
        source = value(env, "KAFKA_EVENT_SOURCE", "");
        userTopic = value(env, "KAFKA_USER_TOPIC", "");
        adminTopic = value(env, "KAFKA_ADMIN_TOPIC", "");
        securityTopic = value(env, "KAFKA_SECURITY_TOPIC", "");
        deadLetterTopic = value(env, "KAFKA_DEAD_LETTER_TOPIC", "");
        privacyIpMode = value(env, "KAFKA_PRIVACY_IP_MODE", "omit");
        ipHmacKey = privacyIpMode.equals("hmac")
            ? readSecret(required(env, "KAFKA_IP_HMAC_KEY_FILE")) : new byte[0];
        pollIntervalMs = integer(env, "KAFKA_OUTBOX_POLL_INTERVAL_MS", 500, 50, 60000);
        claimBatchSize = integer(env, "KAFKA_OUTBOX_CLAIM_BATCH_SIZE", 100, 1, 1000);
        claimSeconds = integer(env, "KAFKA_OUTBOX_CLAIM_SECONDS", 180, 5, 3600);
        claimSafetyMarginMs = integer(env, "KAFKA_OUTBOX_CLAIM_SAFETY_MARGIN_MS", 5000, 1000, 60000);
        maxAttempts = integer(env, "KAFKA_OUTBOX_MAX_ATTEMPTS", 12, 1, 1000);
        baseBackoffMs = integer(env, "KAFKA_OUTBOX_BASE_BACKOFF_MS", 250, 10, 60000);
        deliveryTimeoutMs = integer(env, "KAFKA_DELIVERY_TIMEOUT_MS", 120000, 1000, 600000);
        requestTimeoutMs = integer(env, "KAFKA_REQUEST_TIMEOUT_MS", 30000, 1000, deliveryTimeoutMs);
        backlogMaxRows = integer(env, "KAFKA_OUTBOX_MAX_ROWS", 100000, 100, 100000000);
        backlogMaxAgeSeconds = integer(env, "KAFKA_OUTBOX_MAX_AGE_SECONDS", 3600, 10, 604800);
        retentionSeconds = integer(env, "KAFKA_OUTBOX_RETENTION_SECONDS", 604800, 60, 31536000);
        expectedPartitions = integer(env, "KAFKA_RUNTIME_EXPECTED_TOPIC_PARTITIONS", 3, 1, 1000);
        expectedReplicationFactor = integer(env, "KAFKA_RUNTIME_EXPECTED_TOPIC_REPLICATION_FACTOR", 1, 1, 15);
        workerId = workerId(clientId);
        validate(env);
        producerProperties = producerProperties(env);
    }

    static ProviderConfig load() {
        return new ProviderConfig(System.getenv());
    }

    static ProviderConfig from(Map<String, String> env) {
        return new ProviderConfig(env);
    }

    private void validate(Map<String, String> env) {
        if (!enabled) return;
        required(env, "KAFKA_EVENT_SOURCE");
        required(env, "KAFKA_USER_TOPIC");
        required(env, "KAFKA_ADMIN_TOPIC");
        required(env, "KAFKA_SECURITY_TOPIC");
        required(env, "KAFKA_DEAD_LETTER_TOPIC");
        if (!Set.of("bundled", "external").contains(runtimeMode)) {
            throw new IllegalArgumentException("KAFKA_RUNTIME_MODE must be bundled or external when export is enabled");
        }
        if (bootstrapServers.isBlank()) throw new IllegalArgumentException("KAFKA_BOOTSTRAP_SERVERS is required");
        if ((long) claimSeconds * 1000L < (long) deliveryTimeoutMs + claimSafetyMarginMs) {
            throw new IllegalArgumentException(
                "outbox claim duration must cover Kafka delivery timeout plus the safety margin"
            );
        }
        if (!Set.of("omit", "hmac", "plain").contains(privacyIpMode)) {
            throw new IllegalArgumentException("KAFKA_PRIVACY_IP_MODE must be omit, hmac, or plain");
        }
        if (privacyIpMode.equals("plain") && !bool(env, "KAFKA_PRIVACY_PLAIN_IP_APPROVED", false)) {
            throw new IllegalArgumentException("plain IP export requires an explicit privacy approval flag");
        }
        String protocol = value(env, "KAFKA_SECURITY_PROTOCOL", runtimeMode.equals("bundled") ? "PLAINTEXT" : "");
        if (runtimeMode.equals("external") && !Set.of("SSL", "SASL_SSL").contains(protocol)) {
            throw new IllegalArgumentException("external Kafka requires SSL or SASL_SSL");
        }
        if (runtimeMode.equals("external") && !bool(env, "KAFKA_SSL_ENDPOINT_IDENTIFICATION", true)) {
            throw new IllegalArgumentException("external Kafka hostname verification cannot be disabled");
        }
        if (runtimeMode.equals("external")) required(env, "KAFKA_SSL_TRUSTSTORE_LOCATION");
        if (protocol.equals("SASL_SSL")) {
            required(env, "KAFKA_SASL_MECHANISM");
            required(env, "KAFKA_SASL_USERNAME_FILE");
            required(env, "KAFKA_SASL_PASSWORD_FILE");
        }
    }

    private Properties producerProperties(Map<String, String> env) {
        Properties properties = new Properties();
        if (!enabled) return properties;
        int lingerMs = integer(env, "KAFKA_LINGER_MS", 5, 0, 60000);
        if (deliveryTimeoutMs < requestTimeoutMs + lingerMs) {
            throw new IllegalArgumentException("Kafka delivery timeout must cover request timeout plus linger");
        }
        properties.setProperty("bootstrap.servers", bootstrapServers);
        properties.setProperty("client.id", clientId);
        properties.setProperty("acks", "all");
        properties.setProperty("enable.idempotence", "true");
        properties.setProperty("retries", Integer.toString(Integer.MAX_VALUE));
        properties.setProperty("max.in.flight.requests.per.connection", "5");
        properties.setProperty("delivery.timeout.ms", Integer.toString(deliveryTimeoutMs));
        properties.setProperty("request.timeout.ms", Integer.toString(requestTimeoutMs));
        properties.setProperty("max.block.ms", Integer.toString(deliveryTimeoutMs));
        properties.setProperty("linger.ms", Integer.toString(lingerMs));
        properties.setProperty("batch.size", Integer.toString(integer(env, "KAFKA_BATCH_SIZE", 16384, 1, 1048576)));
        properties.setProperty("compression.type", "none");
        properties.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        String protocol = value(env, "KAFKA_SECURITY_PROTOCOL", runtimeMode.equals("bundled") ? "PLAINTEXT" : "");
        properties.setProperty("security.protocol", protocol);
        if (runtimeMode.equals("external")) {
            properties.setProperty("ssl.endpoint.identification.algorithm", "https");
            properties.setProperty("ssl.truststore.location", required(env, "KAFKA_SSL_TRUSTSTORE_LOCATION"));
            optionalFileSecret(properties, env, "ssl.truststore.password", "KAFKA_SSL_TRUSTSTORE_PASSWORD_FILE");
            optional(properties, env, "ssl.truststore.type", "KAFKA_SSL_TRUSTSTORE_TYPE");
            optional(properties, env, "ssl.keystore.location", "KAFKA_SSL_KEYSTORE_LOCATION");
            optionalFileSecret(properties, env, "ssl.keystore.password", "KAFKA_SSL_KEYSTORE_PASSWORD_FILE");
            optionalFileSecret(properties, env, "ssl.key.password", "KAFKA_SSL_KEY_PASSWORD_FILE");
            if (protocol.equals("SASL_SSL")) {
                String mechanism = required(env, "KAFKA_SASL_MECHANISM");
                if (!Set.of("SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism)) {
                    throw new IllegalArgumentException("only SCRAM-SHA-256 or SCRAM-SHA-512 is supported");
                }
                String username = textSecret(required(env, "KAFKA_SASL_USERNAME_FILE"));
                String password = textSecret(required(env, "KAFKA_SASL_PASSWORD_FILE"));
                properties.setProperty("sasl.mechanism", mechanism);
                properties.setProperty("sasl.jaas.config",
                    "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" +
                    jaas(username) + "\" password=\"" + jaas(password) + "\";");
            }
        }
        return properties;
    }

    private static void optional(Properties target, Map<String, String> env, String property, String variable) {
        String value = env.get(variable);
        if (value != null && !value.isBlank()) target.setProperty(property, value);
    }

    private static void optionalFileSecret(Properties target, Map<String, String> env, String property, String variable) {
        String file = env.get(variable);
        if (file != null && !file.isBlank()) target.setProperty(property, textSecret(file));
    }

    private static String jaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static byte[] readSecret(String file) {
        try {
            byte[] value = Files.readAllBytes(Path.of(file));
            if (value.length < 16) throw new IllegalArgumentException("secret file is too short");
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("required secret file is unreadable", exception);
        }
    }

    private static String textSecret(String file) {
        String value = new String(readSecret(file), StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("secret file is empty");
        return value;
    }

    private static boolean bool(Map<String, String> env, String name, boolean fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static int integer(Map<String, String> env, String name, int fallback, int minimum, int maximum) {
        String raw = env.get(name);
        int value = raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is outside its allowed range");
        return value;
    }

    private static String value(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String workerId(String clientId) {
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { host = "unknown-host"; }
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        String prefix = (clientId + "-" + host + "-" + runtime)
            .replaceAll("[^A-Za-z0-9._-]", "-");
        if (prefix.length() > 80) prefix = prefix.substring(0, 80);
        return prefix + "-" + UUID.randomUUID();
    }
}
