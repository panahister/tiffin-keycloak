package io.portable.identity.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;

final class IdentityEventMapper {
    private static final Set<String> AUTHENTICATION = Set.of(
        "LOGIN", "LOGIN_ERROR", "IDENTITY_PROVIDER_LOGIN", "IDENTITY_PROVIDER_LOGIN_ERROR",
        "CODE_TO_TOKEN", "CODE_TO_TOKEN_ERROR", "AUTHREQID_TO_TOKEN", "AUTHREQID_TO_TOKEN_ERROR",
        "INTROSPECT_TOKEN", "INTROSPECT_TOKEN_ERROR", "VALIDATE_ACCESS_TOKEN", "VALIDATE_ACCESS_TOKEN_ERROR"
    );
    private static final Set<String> CREDENTIAL = Set.of(
        "UPDATE_PASSWORD", "UPDATE_PASSWORD_ERROR", "UPDATE_TOTP", "UPDATE_TOTP_ERROR",
        "REMOVE_TOTP", "REMOVE_TOTP_ERROR", "RESET_PASSWORD", "RESET_PASSWORD_ERROR",
        "SEND_RESET_PASSWORD", "SEND_RESET_PASSWORD_ERROR", "VERIFY_EMAIL", "VERIFY_EMAIL_ERROR"
    );
    private static final Set<String> SESSION = Set.of(
        "LOGOUT", "LOGOUT_ERROR", "RESTART_AUTHENTICATION",
        "RESTART_AUTHENTICATION_ERROR", "IMPERSONATE", "IMPERSONATE_ERROR"
    );
    private static final Set<String> TOKEN = Set.of(
        "REFRESH_TOKEN", "REFRESH_TOKEN_ERROR", "TOKEN_EXCHANGE", "TOKEN_EXCHANGE_ERROR",
        "OAUTH2_DEVICE_CODE_TO_TOKEN", "OAUTH2_DEVICE_CODE_TO_TOKEN_ERROR",
        "CLIENT_LOGIN", "CLIENT_LOGIN_ERROR"
    );
    private static final Set<String> ACCOUNT = Set.of(
        "REGISTER", "REGISTER_ERROR", "UPDATE_PROFILE", "UPDATE_PROFILE_ERROR",
        "DELETE_ACCOUNT", "DELETE_ACCOUNT_ERROR", "UPDATE_EMAIL", "UPDATE_EMAIL_ERROR"
    );

    private final ProviderConfig config;

    IdentityEventMapper(ProviderConfig config) {
        this.config = config;
    }

    OutboxRecord user(Event event) {
        String rawType = event.getType() == null ? "UNKNOWN" : event.getType().toString();
        return rawUser(
            event.getId(), rawType, event.getTime(), event.getRealmId(), event.getUserId(),
            event.getClientId(), event.getSessionId(), event.getIpAddress(), event.getError()
        );
    }

    OutboxRecord rawUser(String keycloakId, String rawType, long occurredAt, String realmId,
                         String userId, String clientId, String sessionId, String ipAddress,
                         String error) {
        String eventId = stableId("user", keycloakId);
        String capturedAt = iso(Instant.now());
        String subject = opaqueSubject(realmId, userId, sessionId, clientId, eventId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "1.0");
        data.put("realmId", opaque(realmId, "unknown-realm", 128));
        putOptional(data, "userId", nullableOpaque(userId, 128));
        putOptional(data, "clientId", nullableOpaque(clientId, 256));
        putOptional(data, "sessionId", nullableOpaque(sessionId, 128));
        data.put("keycloakEventType", bounded(rawType, 128, "UNKNOWN"));
        data.put("outcome", error == null ? "success" : "failure");
        data.put("classification", classify(rawType));
        data.put("occurredAt", iso(Instant.ofEpochMilli(occurredAt > 0 ? occurredAt : System.currentTimeMillis())));
        data.put("capturedAt", capturedAt);
        String protectedIp = protectedIp(ipAddress);
        if (protectedIp != null) data.put(config.privacyIpMode.equals("plain") ? "ipAddress" : "ipAddressHmac", protectedIp);
        String payload = cloudEvent(eventId, "identity.user-event.v1",
            "urn:portable:keycloak:identity:user-event:v1", subject, capturedAt, data);
        return new OutboxRecord(UUID.randomUUID().toString(), eventId, "user", config.userTopic,
            partitionKey(realmId, userId, sessionId, clientId, eventId), "1.0", payload, 0);
    }

    OutboxRecord admin(AdminEvent event) {
        AuthDetails auth = event.getAuthDetails();
        String eventId = stableId("admin", event.getId());
        String capturedAt = iso(Instant.now());
        String rawOperation = event.getOperationType() == null ? "UNKNOWN" : event.getOperationType().toString();
        String rawResource = event.getResourceType() == null ? "UNKNOWN" : event.getResourceType().toString();
        String resourcePath = opaqueResourcePath(rawResource, event.getResourcePath());
        String realmId = event.getRealmId();
        String authRealmId = auth == null ? null : auth.getRealmId();
        String authClientId = auth == null ? null : auth.getClientId();
        String authUserId = auth == null ? null : auth.getUserId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "1.0");
        data.put("realmId", opaque(realmId, "unknown-realm", 128));
        putOptional(data, "authRealmId", nullableOpaque(authRealmId, 128));
        putOptional(data, "authClientId", nullableOpaque(authClientId, 256));
        putOptional(data, "authUserId", nullableOpaque(authUserId, 128));
        data.put("keycloakEventType", bounded(rawOperation, 128, "UNKNOWN"));
        data.put("resourceType", bounded(rawResource, 128, "UNKNOWN"));
        data.put("resourcePath", resourcePath);
        data.put("outcome", event.getError() == null ? "success" : "failure");
        data.put("classification", rawOperation.equals("UNKNOWN") || rawResource.equals("UNKNOWN")
            ? "unclassified" : "administration");
        data.put("occurredAt", iso(Instant.ofEpochMilli(event.getTime() > 0 ? event.getTime() : System.currentTimeMillis())));
        data.put("capturedAt", capturedAt);
        String subject = opaqueSubject(realmId, authUserId, null, authClientId, eventId);
        String payload = cloudEvent(eventId, "identity.admin-event.v1",
            "urn:portable:keycloak:identity:admin-event:v1", subject, capturedAt, data);
        return new OutboxRecord(UUID.randomUUID().toString(), eventId, "admin", config.adminTopic,
            partitionKey(realmId, authUserId, null, authClientId, eventId), "1.0", payload, 0);
    }

    OutboxRecord securityCopy(OutboxRecord source) {
        return new OutboxRecord(UUID.randomUUID().toString(), source.eventId(), "security",
            config.securityTopic, source.partitionKey(), source.schemaVersion(), source.payload(), 0);
    }

    boolean securityRelevant(OutboxRecord record) {
        if (record.kind().equals("admin")) return true;
        String payload = record.payload();
        return payload.contains("\"outcome\":\"failure\"") ||
            payload.contains("\"classification\":\"authentication\"") ||
            payload.contains("\"classification\":\"credential\"") ||
            payload.contains("\"classification\":\"session\"");
    }

    private String cloudEvent(String id, String type, String schema, String subject,
                              String capturedAt, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", id);
        envelope.put("source", config.source);
        envelope.put("type", type);
        envelope.put("time", capturedAt);
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", schema);
        envelope.put("subject", subject);
        envelope.put("data", data);
        return Json.encode(envelope);
    }

    static String classify(String rawType) {
        String type = rawType == null ? "UNKNOWN" : rawType.toUpperCase(Locale.ROOT);
        if (AUTHENTICATION.contains(type)) return "authentication";
        if (CREDENTIAL.contains(type)) return "credential";
        if (SESSION.contains(type)) return "session";
        if (TOKEN.contains(type)) return "token";
        if (ACCOUNT.contains(type)) return "account";
        if (type.contains("CONSENT")) return "consent";
        if (type.contains("IDENTITY_PROVIDER") || type.contains("FEDERATED")) return "federation";
        return "unclassified";
    }

    private String protectedIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || config.privacyIpMode.equals("omit")) return null;
        if (config.privacyIpMode.equals("plain")) return bounded(ipAddress, 64, null);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.ipHmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ipAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot protect IP address", exception);
        }
    }

    private static String stableId(String kind, String keycloakId) {
        return "keycloak-" + kind + "-" + (keycloakId == null || keycloakId.isBlank()
            ? UUID.randomUUID() : bounded(keycloakId, 120, "unknown"));
    }

    private static String partitionKey(String realmId, String userId, String sessionId,
                                       String clientId, String eventId) {
        if (realmId != null && userId != null) return bounded(realmId, 128, "realm") + ":" + bounded(userId, 128, "user");
        if (realmId != null && sessionId != null) return bounded(realmId, 128, "realm") + ":session:" + bounded(sessionId, 128, "session");
        if (realmId != null && clientId != null) return bounded(realmId, 128, "realm") + ":client:" + bounded(clientId, 128, "client");
        return "event:" + digest(eventId);
    }

    private static String opaqueSubject(String realmId, String userId, String sessionId,
                                        String clientId, String eventId) {
        return partitionKey(realmId, userId, sessionId, clientId, eventId);
    }

    private static String opaqueResourcePath(String resourceType, String rawPath) {
        return bounded(resourceType, 128, "UNKNOWN") + "/sha256:" + digest(rawPath == null ? "unknown" : rawPath);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void putOptional(Map<String, Object> target, String name, String value) {
        if (value != null) target.put(name, value);
    }

    private static String nullableOpaque(String value, int maximum) {
        return value == null || value.isBlank() ? null : bounded(value, maximum, null);
    }

    private static String opaque(String value, String fallback, int maximum) {
        return value == null || value.isBlank() ? fallback : bounded(value, maximum, fallback);
    }

    private static String bounded(String value, int maximum, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.replaceAll("[\\r\\n\\t]", "_");
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static String iso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
