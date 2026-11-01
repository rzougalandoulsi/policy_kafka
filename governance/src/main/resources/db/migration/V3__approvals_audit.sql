-- Approval workflow and audit trail.

CREATE TABLE approval_configuration (
    id            TEXT PRIMARY KEY,
    version       BIGSERIAL   NOT NULL UNIQUE,
    content       JSONB       NOT NULL,
    status        TEXT        NOT NULL,          -- DRAFT | ACTIVE | RETIRED
    created_by    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    activated_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_approval_configuration_active ON approval_configuration ((status)) WHERE status = 'ACTIVE';

INSERT INTO approval_configuration (id, content, status, created_by, created_at, activated_at)
VALUES ('apr-initial', '{
  "approverRoles": ["governance-approver"],
  "requiredApprovals": 1,
  "separationOfDuties": true,
  "ttlHours": 72
}'::jsonb, 'ACTIVE', 'system', now(), now());

CREATE TABLE approval_request (
    id                  TEXT PRIMARY KEY,
    request_id          TEXT        NOT NULL REFERENCES manifest_request (id),
    stage               TEXT        NOT NULL,     -- REQUEST | ACTION_PLAN
    decision_id         TEXT        NOT NULL REFERENCES governance_decision (id),
    plan_id             TEXT,
    plan_digest         TEXT,
    digest              TEXT        NOT NULL,     -- manifest digest (stage 1) or plan digest (stage 2)
    status              TEXT        NOT NULL,     -- PENDING | APPROVED | REJECTED | EXPIRED | CANCELLED
    requester_id        TEXT        NOT NULL,
    required_approvals  INT         NOT NULL,
    approver_roles      JSONB       NOT NULL,
    separation_of_duties BOOLEAN    NOT NULL,
    requirements        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    expires_at          TIMESTAMPTZ NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    resolution_reason   TEXT
);
CREATE INDEX ix_approval_request_status ON approval_request (status, expires_at);
CREATE INDEX ix_approval_request_request ON approval_request (request_id);

CREATE TABLE approval_decision (
    id            TEXT PRIMARY KEY,
    approval_id   TEXT        NOT NULL REFERENCES approval_request (id),
    approver_id   TEXT        NOT NULL,
    verdict       TEXT        NOT NULL,           -- APPROVE | REJECT
    comment       TEXT,
    decided_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_approval_decision_once UNIQUE (approval_id, approver_id)
);

CREATE TABLE audit_record (
    id            BIGSERIAL PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor_id      TEXT        NOT NULL,
    action        TEXT        NOT NULL,
    target_type   TEXT        NOT NULL,
    target_id     TEXT        NOT NULL,
    request_id    TEXT,
    details       JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_record_target ON audit_record (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_record_request ON audit_record (request_id, occurred_at DESC);
CREATE INDEX ix_audit_record_time ON audit_record (occurred_at DESC);
