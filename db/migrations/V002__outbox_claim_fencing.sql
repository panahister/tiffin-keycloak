ALTER TABLE kc_identity_event_outbox
    ADD COLUMN IF NOT EXISTS claim_generation bigint NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM kc_identity_event_outbox WHERE claim_generation < 0
    ) THEN
        RAISE EXCEPTION 'negative outbox claim generation is invalid';
    END IF;
END $$;

-- Claims created by the pre-fencing publisher cannot be safely attributed to a
-- generation. Make them immediately reclaimable without changing attempts or
-- payloads. Published and dead-lettered rows remain untouched.
UPDATE kc_identity_event_outbox
SET state = 'pending', claimed_by = NULL, claimed_until = NULL,
    next_attempt_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP)
WHERE state = 'claimed';

ALTER TABLE kc_identity_event_outbox
    DROP CONSTRAINT IF EXISTS kc_identity_event_outbox_claim_generation_check;
ALTER TABLE kc_identity_event_outbox
    ADD CONSTRAINT kc_identity_event_outbox_claim_generation_check
    CHECK (claim_generation >= 0);
