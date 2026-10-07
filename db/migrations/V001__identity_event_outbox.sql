CREATE TABLE IF NOT EXISTS kc_identity_event_outbox (
    outbox_id varchar(64) PRIMARY KEY,
    event_id varchar(160) NOT NULL,
    event_kind varchar(24) NOT NULL CHECK (event_kind IN ('user', 'admin', 'security')),
    destination_topic varchar(249) NOT NULL,
    partition_key varchar(512) NOT NULL,
    schema_version varchar(32) NOT NULL,
    payload text NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'claimed', 'published', 'dead-lettered')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_by varchar(128),
    claimed_until timestamptz,
    last_error_class varchar(96),
    captured_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    UNIQUE (event_id, destination_topic)
);

CREATE INDEX IF NOT EXISTS kc_identity_event_outbox_drain_idx
    ON kc_identity_event_outbox (state, next_attempt_at, captured_at);
CREATE INDEX IF NOT EXISTS kc_identity_event_outbox_claim_idx
    ON kc_identity_event_outbox (claimed_until) WHERE state = 'claimed';
