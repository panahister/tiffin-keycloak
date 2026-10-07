package io.portable.identity.events;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

final class OutboxRepository {
    private static final String TABLE = "kc_identity_event_outbox";

    void verifySchema(EntityManager entityManager) {
        entityManager.createNativeQuery("SELECT outbox_id FROM " + TABLE + " WHERE 1 = 0").getResultList();
    }

    void capture(EntityManager entityManager, OutboxRecord record, int maximumRows) {
        Number backlog = (Number) entityManager.createNativeQuery(
            "SELECT count(*) FROM " + TABLE + " WHERE state IN ('pending','claimed')"
        ).getSingleResult();
        if (backlog.longValue() >= maximumRows) {
            throw new IllegalStateException("identity event outbox capacity exceeded");
        }
        entityManager.createNativeQuery(
            "INSERT INTO " + TABLE + " (outbox_id,event_id,event_kind,destination_topic,partition_key,schema_version,payload) " +
            "VALUES (?,?,?,?,?,?,?) ON CONFLICT (event_id,destination_topic) DO NOTHING"
        ).setParameter(1, record.outboxId())
            .setParameter(2, record.eventId())
            .setParameter(3, record.kind())
            .setParameter(4, record.topic())
            .setParameter(5, record.partitionKey())
            .setParameter(6, record.schemaVersion())
            .setParameter(7, record.payload())
            .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    List<OutboxRecord> claim(EntityManager entityManager, ProviderConfig config) {
        List<Object[]> rows = entityManager.createNativeQuery(
            "SELECT outbox_id,event_id,event_kind,destination_topic,partition_key,schema_version,payload,attempt_count,claim_generation " +
            "FROM " + TABLE + " WHERE ((state='pending' AND next_attempt_at<=CURRENT_TIMESTAMP) " +
            "OR (state='claimed' AND claimed_until<CURRENT_TIMESTAMP)) " +
            "ORDER BY captured_at,outbox_id LIMIT ? FOR UPDATE SKIP LOCKED"
        ).setParameter(1, config.claimBatchSize).getResultList();
        List<OutboxRecord> result = new ArrayList<>();
        for (Object[] row : rows) {
            OutboxRecord record = new OutboxRecord(
                text(row[0]), text(row[1]), text(row[2]), text(row[3]), text(row[4]),
                text(row[5]), text(row[6]), ((Number) row[7]).intValue()
            );
            long nextGeneration = ((Number) row[8]).longValue() + 1L;
            int updated = entityManager.createNativeQuery(
                "UPDATE " + TABLE + " SET state='claimed',claimed_by=?,claim_generation=?," +
                "claimed_until=CURRENT_TIMESTAMP+(? * INTERVAL '1 second') " +
                "WHERE outbox_id=? AND ((state='pending' AND next_attempt_at<=CURRENT_TIMESTAMP) " +
                "OR (state='claimed' AND claimed_until<CURRENT_TIMESTAMP))"
            ).setParameter(1, config.workerId).setParameter(2, nextGeneration)
                .setParameter(3, config.claimSeconds).setParameter(4, record.outboxId()).executeUpdate();
            if (updated == 1) result.add(record.claimed(config.workerId, nextGeneration));
        }
        return result;
    }

    boolean acknowledge(EntityManager entityManager, OutboxRecord record) {
        return entityManager.createNativeQuery(
            "UPDATE " + TABLE + " SET state='published',published_at=CURRENT_TIMESTAMP,claimed_by=NULL,claimed_until=NULL,last_error_class=NULL " +
            "WHERE outbox_id=? AND state='claimed' AND claimed_by=? AND claim_generation=?"
        ).setParameter(1, record.outboxId()).setParameter(2, record.claimOwner())
            .setParameter(3, record.claimGeneration()).executeUpdate() == 1;
    }

    boolean renew(EntityManager entityManager, OutboxRecord record, int claimSeconds) {
        return entityManager.createNativeQuery(
            "UPDATE " + TABLE + " SET claimed_until=CURRENT_TIMESTAMP+(? * INTERVAL '1 second') " +
            "WHERE outbox_id=? AND state='claimed' AND claimed_by=? AND claim_generation=?"
        ).setParameter(1, claimSeconds).setParameter(2, record.outboxId())
            .setParameter(3, record.claimOwner()).setParameter(4, record.claimGeneration())
            .executeUpdate() == 1;
    }

    boolean retry(EntityManager entityManager, OutboxRecord record, String errorClass, long backoffMs) {
        return entityManager.createNativeQuery(
            "UPDATE " + TABLE + " SET state='pending',attempt_count=attempt_count+1," +
            "next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 millisecond'),claimed_by=NULL,claimed_until=NULL,last_error_class=? " +
            "WHERE outbox_id=? AND state='claimed' AND claimed_by=? AND claim_generation=?"
        ).setParameter(1, backoffMs).setParameter(2, errorClass)
            .setParameter(3, record.outboxId()).setParameter(4, record.claimOwner())
            .setParameter(5, record.claimGeneration()).executeUpdate() == 1;
    }

    boolean deadLetter(EntityManager entityManager, OutboxRecord record, String errorClass) {
        return entityManager.createNativeQuery(
            "UPDATE " + TABLE + " SET state='dead-lettered',attempt_count=attempt_count+1,published_at=CURRENT_TIMESTAMP," +
            "claimed_by=NULL,claimed_until=NULL,last_error_class=? WHERE outbox_id=? AND state='claimed' " +
            "AND claimed_by=? AND claim_generation=?"
        ).setParameter(1, errorClass).setParameter(2, record.outboxId())
            .setParameter(3, record.claimOwner()).setParameter(4, record.claimGeneration())
            .executeUpdate() == 1;
    }

    long pendingCount(EntityManager entityManager) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT count(*) FROM " + TABLE + " WHERE state IN ('pending','claimed')"
        ).getSingleResult()).longValue();
    }

    long oldestPendingAgeSeconds(EntityManager entityManager) {
        Number value = (Number) entityManager.createNativeQuery(
            "SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-MIN(captured_at))),0) " +
            "FROM " + TABLE + " WHERE state IN ('pending','claimed')"
        ).getSingleResult();
        return value.longValue();
    }

    void cleanup(EntityManager entityManager, int retentionSeconds) {
        entityManager.createNativeQuery(
            "DELETE FROM " + TABLE + " WHERE state='published' AND published_at < CURRENT_TIMESTAMP-(? * INTERVAL '1 second')"
        ).setParameter(1, retentionSeconds).executeUpdate();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
