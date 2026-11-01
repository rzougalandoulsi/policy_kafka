-- Transactional outbox, idempotent inbox and dead letters for the Kafka handshake.

CREATE TABLE kafka_outbox (
    id              TEXT PRIMARY KEY,
    topic           TEXT        NOT NULL,
    message_key     TEXT        NOT NULL,
    record_type     TEXT        NOT NULL,       -- Avro full name
    payload         JSONB       NOT NULL,       -- Avro JSON encoding
    headers         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    request_id      TEXT,
    status          TEXT        NOT NULL,       -- PENDING | SENT | FAILED
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_kafka_outbox_pending ON kafka_outbox (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE kafka_inbox (
    event_id        TEXT PRIMARY KEY,
    topic           TEXT        NOT NULL,
    partition_no    INT         NOT NULL,
    offset_no       BIGINT      NOT NULL,
    event_type      TEXT        NOT NULL,
    request_id      TEXT        NOT NULL,
    record_type     TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL,       -- PROCESSED | IGNORED | RETRY | DEAD
    outcome         TEXT,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    received_at     TIMESTAMPTZ NOT NULL,
    processed_at    TIMESTAMPTZ,
    CONSTRAINT uq_kafka_inbox_position UNIQUE (topic, partition_no, offset_no)
);
CREATE INDEX ix_kafka_inbox_retry ON kafka_inbox (next_attempt_at) WHERE status = 'RETRY';

CREATE TABLE kafka_dead_letter (
    id            BIGSERIAL PRIMARY KEY,
    topic         TEXT        NOT NULL,
    partition_no  INT         NOT NULL,
    offset_no     BIGINT      NOT NULL,
    message_key   TEXT,
    event_id      TEXT,
    request_id    TEXT,
    reason        TEXT        NOT NULL,
    error         TEXT,
    raw_value     BYTEA,
    recorded_at   TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ
);
CREATE INDEX ix_kafka_dead_letter_open ON kafka_dead_letter (recorded_at) WHERE resolved_at IS NULL;
