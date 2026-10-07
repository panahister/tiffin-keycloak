BEGIN;

DELETE FROM kc_identity_event_outbox WHERE event_id LIKE 'hardening-test-%';

INSERT INTO kc_identity_event_outbox (
    outbox_id,event_id,event_kind,destination_topic,partition_key,schema_version,payload
) VALUES (
    'hardening-test-1','hardening-test-1','user','test.identity.user-events.v1',
    'opaque-test-key','1.0','{}'
);

UPDATE kc_identity_event_outbox
SET state='claimed', claimed_by='worker-a', claim_generation=claim_generation+1,
    claimed_until=CURRENT_TIMESTAMP + INTERVAL '10 seconds'
WHERE event_id='hardening-test-1' AND state='pending';

UPDATE kc_identity_event_outbox
SET claimed_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
WHERE event_id='hardening-test-1';

UPDATE kc_identity_event_outbox
SET state='claimed', claimed_by='worker-b', claim_generation=claim_generation+1,
    claimed_until=CURRENT_TIMESTAMP + INTERVAL '10 seconds'
WHERE event_id='hardening-test-1' AND state='claimed' AND claimed_until<CURRENT_TIMESTAMP;

DO $$
DECLARE changed integer;
BEGIN
    UPDATE kc_identity_event_outbox SET state='published'
    WHERE event_id='hardening-test-1' AND state='claimed'
      AND claimed_by='worker-a' AND claim_generation=1;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 0 THEN RAISE EXCEPTION 'stale worker acknowledgement escaped fencing'; END IF;

    UPDATE kc_identity_event_outbox SET state='pending', attempt_count=attempt_count+1
    WHERE event_id='hardening-test-1' AND state='claimed'
      AND claimed_by='worker-a' AND claim_generation=1;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 0 THEN RAISE EXCEPTION 'stale worker retry escaped fencing'; END IF;

    UPDATE kc_identity_event_outbox SET state='dead-lettered', attempt_count=attempt_count+1
    WHERE event_id='hardening-test-1' AND state='claimed'
      AND claimed_by='worker-a' AND claim_generation=1;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 0 THEN RAISE EXCEPTION 'stale worker DLQ transition escaped fencing'; END IF;

    UPDATE kc_identity_event_outbox SET state='published', published_at=CURRENT_TIMESTAMP
    WHERE event_id='hardening-test-1' AND state='claimed'
      AND claimed_by='worker-b' AND claim_generation=2;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 1 THEN RAISE EXCEPTION 'current worker could not acknowledge'; END IF;
END $$;

ROLLBACK;
