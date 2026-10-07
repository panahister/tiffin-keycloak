package io.portable.identity.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class KafkaAdminConfig {
    final Properties properties;
    final List<String> topics;
    final int partitions;
    final short replicationFactor;
    final int minimumInSyncReplicas;
    final String retentionMs;
    final boolean production;

    private KafkaAdminConfig(Map<String, String> env) {
        production = bool(env, "KAFKA_ADMIN_PRODUCTION", false);
        String protocol = required(env, "KAFKA_ADMIN_SECURITY_PROTOCOL");
        if (production && !Set.of("SSL", "SASL_SSL").contains(protocol)) {
            throw new IllegalArgumentException("production topic administration requires SSL or SASL_SSL");
        }
        partitions = integer(env, "KAFKA_ADMIN_TOPIC_PARTITIONS", 3, 1, 1000);
        replicationFactor = (short) integer(env, "KAFKA_ADMIN_TOPIC_REPLICATION_FACTOR", 1, 1, 15);
        minimumInSyncReplicas = integer(env, "KAFKA_ADMIN_TOPIC_MIN_INSYNC_REPLICAS", 1, 1, 15);
        if (minimumInSyncReplicas > replicationFactor) {
            throw new IllegalArgumentException("topic min ISR cannot exceed replication");
        }
        if (production && (replicationFactor < 3 || minimumInSyncReplicas < 2)) {
            throw new IllegalArgumentException("production topics require replication >= 3 and min ISR >= 2");
        }
        retentionMs = env.getOrDefault("KAFKA_ADMIN_TOPIC_RETENTION_MS", "");
        topics = List.of(
            required(env, "KAFKA_ADMIN_USER_TOPIC"),
            required(env, "KAFKA_ADMIN_ADMIN_TOPIC"),
            required(env, "KAFKA_ADMIN_SECURITY_TOPIC"),
            required(env, "KAFKA_ADMIN_DEAD_LETTER_TOPIC")
        );
        properties = new Properties();
        properties.setProperty("bootstrap.servers", required(env, "KAFKA_ADMIN_BOOTSTRAP_SERVERS"));
        properties.setProperty("client.id", required(env, "KAFKA_ADMIN_CLIENT_ID"));
        properties.setProperty("security.protocol", protocol);
        properties.setProperty("request.timeout.ms", "30000");
        properties.setProperty("default.api.timeout.ms", "30000");
        if (Set.of("SSL", "SASL_SSL").contains(protocol)) {
            properties.setProperty("ssl.endpoint.identification.algorithm", "https");
            properties.setProperty("ssl.truststore.location", required(env, "KAFKA_ADMIN_SSL_TRUSTSTORE_LOCATION"));
            optionalFile(properties, env, "ssl.truststore.password", "KAFKA_ADMIN_SSL_TRUSTSTORE_PASSWORD_FILE");
            optional(properties, env, "ssl.keystore.location", "KAFKA_ADMIN_SSL_KEYSTORE_LOCATION");
            optionalFile(properties, env, "ssl.keystore.password", "KAFKA_ADMIN_SSL_KEYSTORE_PASSWORD_FILE");
            optionalFile(properties, env, "ssl.key.password", "KAFKA_ADMIN_SSL_KEY_PASSWORD_FILE");
        }
        if (protocol.equals("SASL_SSL")) {
            String mechanism = required(env, "KAFKA_ADMIN_SASL_MECHANISM");
            if (!Set.of("SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism)) {
                throw new IllegalArgumentException("unsupported topic-admin SASL mechanism");
            }
            properties.setProperty("sasl.mechanism", mechanism);
            properties.setProperty("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" +
                escape(textSecret(required(env, "KAFKA_ADMIN_SASL_USERNAME_FILE"))) +
                "\" password=\"" + escape(textSecret(required(env, "KAFKA_ADMIN_SASL_PASSWORD_FILE"))) + "\";");
        }
    }

    static KafkaAdminConfig load() { return new KafkaAdminConfig(System.getenv()); }
    static KafkaAdminConfig from(Map<String, String> env) { return new KafkaAdminConfig(env); }

    private static void optional(Properties target, Map<String, String> env, String property, String variable) {
        String value = env.get(variable);
        if (value != null && !value.isBlank()) target.setProperty(property, value);
    }

    private static void optionalFile(Properties target, Map<String, String> env, String property, String variable) {
        String file = env.get(variable);
        if (file != null && !file.isBlank()) target.setProperty(property, textSecret(file));
    }

    private static String textSecret(String file) {
        try {
            String value = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) throw new IllegalArgumentException("topic-admin secret file is empty");
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("topic-admin secret file is unreadable", exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static boolean bool(Map<String, String> env, String name, boolean fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static int integer(Map<String, String> env, String name, int fallback, int minimum, int maximum) {
        String raw = env.get(name);
        int value = raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is outside its allowed range");
        return value;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
