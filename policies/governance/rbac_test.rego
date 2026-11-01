package governance.rbac_test

import data.governance.fixtures as f
import data.governance.rbac

ids(vs) := {v.policyId | some v in vs}

binding(role) := {"principal": "sa-1", "resource": "topic:team-a.orders", "role": role}

test_allowed_role_passes if {
	count(rbac.violations) == 0 with input as f.request("development", [], [binding("DeveloperRead")])
}

test_admin_role_is_hard_deny if {
	vs := rbac.violations with input as f.request("development", [], [binding("CloudClusterAdmin")])
	some v in vs
	v.policyId == "RBAC_ADMIN_ROLE_FORBIDDEN"
	v.severity == "HARD_DENY"
	v.bypassable == false
}

test_admin_role_hard_deny_at_plan_stage if {
	vs := rbac.violations with input as f.plan("development", [f.action("RBAC_ASSIGN", "RBAC", "sa#r#OrganizationAdmin", {"role": "OrganizationAdmin"})])
	"RBAC_ADMIN_ROLE_FORBIDDEN" in ids(vs)
}

test_unknown_role_denied_bypassable if {
	vs := rbac.violations with input as f.request("development", [], [binding("Operator")])
	some v in vs
	v.policyId == "RBAC_ROLE_ALLOWED"
	v.bypassable == true
}

test_environment_restriction if {
	"RBAC_ENV_RESTRICTED" in ids(rbac.violations) with input as f.request("production", [], [binding("ResourceOwner")])
	not "RBAC_ENV_RESTRICTED" in ids(rbac.violations) with input as f.request("production", [], [binding("DeveloperRead")])
}

test_team_role_restriction if {
	inp := object.union(f.request("development", [], [binding("DeveloperWrite")]), {"team": {"id": "team-locked"}})
	"RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as inp
	not "RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as f.request("development", [], [binding("DeveloperWrite")])
}

test_pool_scoped_bindings_are_checked if {
	base := f.request("development", [], [])
	inp := object.union(base, {"manifest": object.union(base.manifest, {"identityPools": [{"name": "p", "principals": [], "rbac": [binding("EnvironmentAdmin")]}]})})
	"RBAC_ADMIN_ROLE_FORBIDDEN" in ids(rbac.violations) with input as inp
}
