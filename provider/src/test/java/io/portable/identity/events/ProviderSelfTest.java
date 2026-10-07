package io.portable.identity.events;

import java.util.HashMap;
import java.util.Map;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

public final class ProviderSelfTest {
    private ProviderSelfTest() {}

    public static void main(String[] arguments) {
        ProviderConfig disabled = ProviderConfig.from(Map.of(
            "KAFKA_EVENT_EXPORT_ENABLED", "false"
        ));
        require(disabled.producerProperties.isEmpty(),
            "disabled event export requires no Kafka connection configuration");

        Map<String, String> values = new HashMap<>();
        values.put("KAFKA_EVENT_EXPORT_ENABLED", "true");
        values.put("KAFKA_EVENT_EXPORT_REQUIRED", "true");
        values.put("KAFKA_RUNTIME_MODE", "bundled");
        values.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        values.put("KAFKA_EVENT_SOURCE", "urn:test:identity");
        values.put("KAFKA_USER_TOPIC", "test.identity.user-events.v1");
        values.put("KAFKA_ADMIN_TOPIC", "test.identity.admin-events.v1");
        values.put("KAFKA_SECURITY_TOPIC", "test.identity.security-events.v1");
        values.put("KAFKA_DEAD_LETTER_TOPIC", "test.identity.dead-letter.v1");
        ProviderConfig config = ProviderConfig.from(values);
        IdentityEventMapper mapper = new IdentityEventMapper(config);

        for (EventType type : EventType.values()) {
            String classification = IdentityEventMapper.classify(type.toString());
            require(classification != null && !classification.isBlank(), "every current user event type is classifiable");
        }
        for (OperationType operation : OperationType.values()) {
            require(operation.toString() != null && !operation.toString().isBlank(), "every admin operation is visible");
        }
        for (ResourceType resource : ResourceType.values()) {
            require(resource.toString() != null && !resource.toString().isBlank(), "every admin resource is visible");
        }
        OutboxRecord unknown = mapper.rawUser("future-id", "FUTURE_EVENT_TYPE", 1,
            "realm-id", "user-id", "client-id", "session-id", "canary-ip", null);
        require(unknown.payload().contains("\"keycloakEventType\":\"FUTURE_EVENT_TYPE\""), "unknown type preserved");
        require(unknown.payload().contains("\"classification\":\"unclassified\""), "unknown type unclassified");
        require(!unknown.payload().contains("canary-ip"), "IP omitted by default");
        String canary = "forbidden-secret-canary";
        require(!unknown.payload().contains(canary), "secret canary absent");
        require(unknown.eventId().equals("keycloak-user-future-id"), "stable Keycloak ID mapping");
        require(mapper.securityCopy(unknown).eventId().equals(unknown.eventId()), "derived copy preserves event ID");
        OutboxRecord sparse = mapper.rawUser("sparse-id", "LOGIN", 1,
            "realm-id", null, null, null, null, null);
        require(!sparse.payload().contains("\"userId\""), "absent user ID omitted rather than emitted as null");
        require(!sparse.payload().contains("\"clientId\""), "absent client ID omitted rather than emitted as null");
        require(!sparse.payload().contains("\"sessionId\""), "absent session ID omitted rather than emitted as null");
        require(OutboxPublisher.safeClass(new IllegalStateException("sensitive-message"))
            .equals("IllegalStateException"), "errors are class-only");
        require(config.producerProperties.getProperty("acks").equals("all"), "all replicas acknowledge delivery");
        require(config.producerProperties.getProperty("enable.idempotence").equals("true"), "producer idempotence enabled");
        require(config.producerProperties.getProperty("max.block.ms")
            .equals(config.producerProperties.getProperty("delivery.timeout.ms")), "metadata blocking is bounded");
        require(config.workerId.length() <= 117 && config.workerId.contains("-"),
            "worker identity is bounded and collision resistant");

        Map<String, String> partialExternal = new HashMap<>(values);
        partialExternal.put("KAFKA_RUNTIME_MODE", "external");
        partialExternal.put("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT");
        expectFailure(() -> ProviderConfig.from(partialExternal), "insecure external configuration rejected");
        Map<String, String> invalidTimeouts = new HashMap<>(values);
        invalidTimeouts.put("KAFKA_DELIVERY_TIMEOUT_MS", "1000");
        invalidTimeouts.put("KAFKA_REQUEST_TIMEOUT_MS", "1000");
        expectFailure(() -> ProviderConfig.from(invalidTimeouts), "inconsistent producer timeouts rejected");
        Map<String, String> unsafeLease = new HashMap<>(values);
        unsafeLease.put("KAFKA_OUTBOX_CLAIM_SECONDS", "30");
        expectFailure(() -> ProviderConfig.from(unsafeLease), "claim shorter than delivery timeout rejected");
        Map<String, String> admin = new HashMap<>();
        admin.put("KAFKA_ADMIN_BOOTSTRAP_SERVERS", "kafka:9092");
        admin.put("KAFKA_ADMIN_CLIENT_ID", "test-topic-admin");
        admin.put("KAFKA_ADMIN_SECURITY_PROTOCOL", "PLAINTEXT");
        admin.put("KAFKA_ADMIN_USER_TOPIC", values.get("KAFKA_USER_TOPIC"));
        admin.put("KAFKA_ADMIN_ADMIN_TOPIC", values.get("KAFKA_ADMIN_TOPIC"));
        admin.put("KAFKA_ADMIN_SECURITY_TOPIC", values.get("KAFKA_SECURITY_TOPIC"));
        admin.put("KAFKA_ADMIN_DEAD_LETTER_TOPIC", values.get("KAFKA_DEAD_LETTER_TOPIC"));
        KafkaAdminConfig adminConfig = KafkaAdminConfig.from(admin);
        require(!adminConfig.properties.containsKey("sasl.jaas.config"),
            "bundled admin configuration has no runtime producer credentials");
        admin.put("KAFKA_ADMIN_PRODUCTION", "true");
        expectFailure(() -> KafkaAdminConfig.from(admin), "production plaintext admin rejected");
        System.out.println("provider-self-test passed userTypes=" + EventType.values().length +
            " adminOperations=" + OperationType.values().length + " adminResources=" + ResourceType.values().length);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
}
