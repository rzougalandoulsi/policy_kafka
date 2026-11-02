-- Flowable correlation for approvals (ADR 0011). The engine owns its ACT_* tables: it creates and upgrades them itself
-- at startup under its own version table (ACT_GE_PROPERTY). Flyway never touches ACT_* and no Governance table is ever
-- created through Flowable.

ALTER TABLE approval_request
    ADD COLUMN engine              TEXT NOT NULL DEFAULT 'LEGACY',   -- LEGACY | FLOWABLE: which implementation resolves this approval
    ADD COLUMN process_instance_id TEXT;                              -- Flowable process instance (business key = approval id)

CREATE UNIQUE INDEX uq_approval_request_process ON approval_request (process_instance_id) WHERE process_instance_id IS NOT NULL;

-- One open approval per request and stage: a replayed or duplicated trigger can never open a second workflow.
CREATE UNIQUE INDEX uq_approval_request_open ON approval_request (request_id, stage) WHERE status = 'PENDING';

-- Drain gauge of the in-flight migration (approvals still owned by the legacy implementation).
CREATE INDEX ix_approval_request_engine_pending ON approval_request (engine) WHERE status = 'PENDING';
