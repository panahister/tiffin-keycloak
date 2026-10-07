package io.portable.identity.events;

record OutboxRecord(
    String outboxId,
    String eventId,
    String kind,
    String topic,
    String partitionKey,
    String schemaVersion,
    String payload,
    int attemptCount,
    String claimOwner,
    long claimGeneration
) {
    OutboxRecord(String outboxId, String eventId, String kind, String topic,
                 String partitionKey, String schemaVersion, String payload,
                 int attemptCount) {
        this(outboxId, eventId, kind, topic, partitionKey, schemaVersion,
            payload, attemptCount, null, 0L);
    }

    OutboxRecord claimed(String owner, long generation) {
        return new OutboxRecord(outboxId, eventId, kind, topic, partitionKey,
            schemaVersion, payload, attemptCount, owner, generation);
    }
}
