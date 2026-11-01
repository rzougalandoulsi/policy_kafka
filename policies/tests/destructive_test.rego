package governance.changes.destructive_test

import data.governance.changes.destructive
import data.governance.fixtures as f

ids(vs) := {v.policyId | some v in vs}

test_topic_delete_allowed_everywhere_in_fixture if {
	count(destructive.violations) == 0 with input as f.plan("production", [f.action("TOPIC_DELETE", "TOPIC", "team-a.a", {})])
}

test_rbac_revoke_restricted_in_production if {
	"RBAC_REVOKE_RESTRICTED" in ids(destructive.violations) with input as f.plan("production", [f.action("RBAC_REVOKE", "RBAC", "sa#r#DeveloperRead", {})])
	not "RBAC_REVOKE_RESTRICTED" in ids(destructive.violations) with input as f.plan("staging", [f.action("RBAC_REVOKE", "RBAC", "sa#r#DeveloperRead", {})])
}

test_identity_pool_delete_restricted if {
	"IDENTITY_POOL_DELETE_RESTRICTED" in ids(destructive.violations) with input as f.plan("staging", [f.action("IDENTITY_POOL_DELETE", "IDENTITY_POOL", "pool", {})])
}

test_creates_are_not_destructive if {
	count(destructive.violations) == 0 with input as f.plan("production", [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {})])
}

test_not_evaluated_at_request_stage if {
	count(destructive.violations) == 0 with input as f.request("production", [], [])
}
