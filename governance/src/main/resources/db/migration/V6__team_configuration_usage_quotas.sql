-- Input contract 2.0: team configuration (system of record unless an external registry is confirmed), repository usage
-- consumed from teams.repo.manifest, quota reservations for in-flight authorised plans, shadow findings.

CREATE TABLE team_configuration (
    team_id             TEXT PRIMARY KEY,
    version             BIGINT      NOT NULL DEFAULT 1,
    authorized_prefixes JSONB       NOT NULL DEFAULT '[]'::jsonb,
    quotas              JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- {"topics": 25, "topicPartitions": 100, ...}
    allowed_roles       JSONB       NOT NULL DEFAULT '[]'::jsonb,   -- [] = global rbac.allowedRoles / rbac.teamRoles apply
    environments        JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- per-environment parameters, passed through to OPA
    source              TEXT        NOT NULL DEFAULT 'governance',  -- governance | external
    source_version      TEXT,
    synced_at           TIMESTAMPTZ,
    comment             TEXT,
    updated_by          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE team_configuration_history (
    id          BIGSERIAL PRIMARY KEY,
    team_id     TEXT        NOT NULL,
    version     BIGINT      NOT NULL,
    content     JSONB       NOT NULL,
    action      TEXT        NOT NULL,   -- CREATED | UPDATED | DELETED
    changed_by  TEXT        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL,
    comment     TEXT
);
CREATE INDEX ix_team_configuration_history_team ON team_configuration_history (team_id, version DESC, id DESC);

-- Confirmed resources per repository (RepoConfirmed on teams.repo.manifest) plus the partitions Governance last saw
-- declared for those topics (RepoConfirmed carries names only). state_version -1 = no confirmed snapshot yet.
CREATE TABLE repository_usage (
    team_id             TEXT        NOT NULL,
    repository_id       TEXT        NOT NULL,
    environment         TEXT        NOT NULL,
    state_version       BIGINT      NOT NULL DEFAULT -1,
    resources           JSONB       NOT NULL DEFAULT '{"topics":[],"identityPools":[],"rbacBindings":[]}'::jsonb,
    declared_partitions JSONB       NOT NULL DEFAULT '{}'::jsonb,
    snapshot_at         TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (team_id, repository_id, environment)
);
CREATE INDEX ix_repository_usage_environment ON repository_usage (environment);

-- Capacity reserved by an authorised plan until the Orchestrator reports a terminal event (or the TTL passes).
CREATE TABLE quota_reservation (
    id             TEXT PRIMARY KEY,
    team_id        TEXT        NOT NULL,
    environment    TEXT        NOT NULL,
    request_id     TEXT        NOT NULL REFERENCES manifest_request (id),
    plan_id        TEXT        NOT NULL,
    decision_id    TEXT        NOT NULL,
    deltas         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    released_at    TIMESTAMPTZ,
    release_reason TEXT
);
CREATE INDEX ix_quota_reservation_open ON quota_reservation (team_id, environment, expires_at) WHERE released_at IS NULL;
CREATE INDEX ix_quota_reservation_request ON quota_reservation (request_id);

-- Violations and approval requirements of policies running in shadow mode: recorded with the decision, never blocking.
CREATE TABLE shadow_finding (
    id            BIGSERIAL PRIMARY KEY,
    decision_id   TEXT        NOT NULL REFERENCES governance_decision (id),
    request_id    TEXT        NOT NULL REFERENCES manifest_request (id),
    plan_id       TEXT,
    stage         TEXT        NOT NULL,
    kind          TEXT        NOT NULL,   -- VIOLATION | APPROVAL
    policy_id     TEXT        NOT NULL,
    severity      TEXT        NOT NULL,
    message       TEXT,
    resource_type TEXT,
    resource_name TEXT,
    recorded_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_shadow_finding_policy ON shadow_finding (policy_id, recorded_at DESC);
CREATE INDEX ix_shadow_finding_request ON shadow_finding (request_id);
