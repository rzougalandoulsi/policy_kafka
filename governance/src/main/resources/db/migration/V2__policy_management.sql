-- Policy configuration (runtime parameters), bundle versions (Rego logic), scoped exceptions.

CREATE TABLE policy_configuration (
    id            TEXT PRIMARY KEY,
    version       BIGSERIAL   NOT NULL UNIQUE,
    content       JSONB       NOT NULL,
    status        TEXT        NOT NULL,          -- DRAFT | ACTIVE | RETIRED
    comment       TEXT,
    created_by    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    activated_at  TIMESTAMPTZ,
    retired_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_policy_configuration_active ON policy_configuration ((status)) WHERE status = 'ACTIVE';

CREATE TABLE policy_bundle_version (
    id             TEXT PRIMARY KEY,
    revision       TEXT        NOT NULL UNIQUE,
    sha256         TEXT        NOT NULL,
    bundle         BYTEA       NOT NULL,
    manifest       JSONB       NOT NULL,
    test_report    JSONB,
    status         TEXT        NOT NULL,         -- UPLOADED | ACTIVE | RETIRED | FAILED
    comment        TEXT,
    uploaded_by    TEXT        NOT NULL,
    uploaded_at    TIMESTAMPTZ NOT NULL,
    activated_at   TIMESTAMPTZ,
    retired_at     TIMESTAMPTZ,
    failure_reason TEXT
);
CREATE UNIQUE INDEX uq_policy_bundle_active ON policy_bundle_version ((status)) WHERE status = 'ACTIVE';

CREATE TABLE policy_exception (
    id               TEXT PRIMARY KEY,
    policy_id        TEXT        NOT NULL,
    principal_id     TEXT,                       -- NULL = any principal of the team
    team_id          TEXT        NOT NULL,
    environment      TEXT        NOT NULL,       -- '*' = any environment
    resource_pattern TEXT        NOT NULL,       -- glob
    action_types     JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- [] = any action type
    reason           TEXT        NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    created_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    revoked_by       TEXT,
    revoked_at       TIMESTAMPTZ,
    revoke_reason    TEXT
);
CREATE INDEX ix_policy_exception_scope ON policy_exception (team_id, environment, expires_at) WHERE revoked_at IS NULL;

-- Seed: initial configuration V1 (ACTIVE). Policy logic itself ships as an OPA bundle uploaded via the admin API.
INSERT INTO policy_configuration (id, content, status, comment, created_by, created_at, activated_at)
VALUES ('cfg-initial', '{
  "naming": {
    "topicPattern": "^[a-z][a-z0-9]*([.-][a-z0-9]+)*$",
    "requireTeamPrefix": true,
    "teamPrefixSeparator": ".",
    "environmentRestrictions": {
      "production": {"forbiddenPatterns": ["^[a-z0-9-]+\\\\.(tmp|test|scratch)(\\\\.|$)"]}
    }
  },
  "rbac": {
    "allowedRoles": ["DeveloperRead", "DeveloperWrite", "DeveloperManage", "ResourceOwner"],
    "forbiddenRoles": ["CloudClusterAdmin", "EnvironmentAdmin", "OrganizationAdmin", "SystemAdmin"],
    "environmentRoles": {"production": ["DeveloperRead", "DeveloperWrite", "DeveloperManage"]},
    "teamRoles": {},
    "sensitiveRoles": ["DeveloperManage", "ResourceOwner"]
  },
  "limits": {
    "maxTopicsPerManifest": 50,
    "maxPartitionsPerTopic": 64,
    "maxPartitionsPerPlan": 512,
    "maxRetentionMs": 2592000000,
    "environmentOverrides": {"development": {"maxPartitionsPerTopic": 12, "maxRetentionMs": 604800000}}
  },
  "destructive": {
    "topicDeleteAllowedEnvironments": ["development", "staging", "production"],
    "rbacRevokeAllowedEnvironments": ["development", "staging", "production"],
    "identityPoolDeleteAllowedEnvironments": ["development", "staging"]
  },
  "approvals": {
    "productionTopicDeletion": true,
    "highImpactPartitionThreshold": 128,
    "sensitiveRoleAssignment": true,
    "sensitiveEnvironments": ["production"]
  }
}'::jsonb, 'ACTIVE', 'Initial configuration', 'system', now(), now());
