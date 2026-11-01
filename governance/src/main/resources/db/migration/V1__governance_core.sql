-- Governance core: requests, decisions, plans, repository state.

CREATE TABLE manifest_request (
    id                  TEXT PRIMARY KEY,
    principal_id        TEXT        NOT NULL,
    idempotency_key     TEXT        NOT NULL,
    team_id             TEXT        NOT NULL,
    repository_id       TEXT        NOT NULL,
    environment         TEXT        NOT NULL,
    manifest            JSONB       NOT NULL,
    manifest_digest     TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    plan_id             TEXT,
    plan_digest         TEXT,
    state_version       BIGINT,
    failure_reason      TEXT,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_manifest_request_idempotency UNIQUE (principal_id, idempotency_key)
);
CREATE INDEX ix_manifest_request_team ON manifest_request (team_id, created_at DESC);
CREATE INDEX ix_manifest_request_status ON manifest_request (status);
CREATE INDEX ix_manifest_request_plan ON manifest_request (plan_id);

CREATE TABLE request_status_history (
    id            BIGSERIAL PRIMARY KEY,
    request_id    TEXT        NOT NULL REFERENCES manifest_request (id),
    from_status   TEXT,
    to_status     TEXT        NOT NULL,
    reason        TEXT,
    event_id      TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_request_status_history_request ON request_status_history (request_id, occurred_at);

CREATE TABLE governance_decision (
    id                    TEXT PRIMARY KEY,
    request_id            TEXT        NOT NULL REFERENCES manifest_request (id),
    plan_id               TEXT,
    stage                 TEXT        NOT NULL,
    decision_type         TEXT        NOT NULL,
    policy_revision       TEXT        NOT NULL,
    configuration_version BIGINT      NOT NULL,
    digest                TEXT        NOT NULL,
    matched_policies      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    violations            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    approval_requirements JSONB       NOT NULL DEFAULT '[]'::jsonb,
    applied_exceptions    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    error_message         TEXT,
    opa_latency_ms        BIGINT,
    evaluated_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_governance_decision_request ON governance_decision (request_id, evaluated_at);

CREATE TABLE proposed_plan (
    plan_id          TEXT PRIMARY KEY,
    request_id       TEXT        NOT NULL REFERENCES manifest_request (id),
    plan_digest      TEXT        NOT NULL,
    manifest_digest  TEXT        NOT NULL,
    state_version    BIGINT      NOT NULL,
    actions          JSONB       NOT NULL,
    event_id         TEXT        NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_proposed_plan_request ON proposed_plan (request_id);

-- Latest confirmed-state version per repository as reported by the Orchestrator's terminal events.
CREATE TABLE repository_state (
    team_id        TEXT NOT NULL,
    repository_id  TEXT NOT NULL,
    environment    TEXT NOT NULL,
    state_version  BIGINT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (team_id, repository_id, environment)
);
