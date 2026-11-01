-- Six independently versioned policy bundles replace the single "governance" bundle.
-- A bundle version now belongs to a bundle name; exactly one ACTIVE version per name forms the policy release.
ALTER TABLE policy_bundle_version ADD COLUMN bundle_name TEXT NOT NULL DEFAULT 'governance';
DROP INDEX IF EXISTS uq_policy_bundle_active;
-- The legacy single bundle is retired: OPA no longer polls that name, so leaving it ACTIVE would only hide the gap.
UPDATE policy_bundle_version SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE' AND bundle_name = 'governance';
CREATE UNIQUE INDEX uq_policy_bundle_active_per_name ON policy_bundle_version (bundle_name) WHERE status = 'ACTIVE';
CREATE INDEX ix_policy_bundle_name ON policy_bundle_version (bundle_name, uploaded_at DESC);

-- Every decision records the release id (policy_revision) and the revision OPA reported per bundle.
ALTER TABLE governance_decision ADD COLUMN policy_revisions JSONB NOT NULL DEFAULT '{}'::jsonb;
