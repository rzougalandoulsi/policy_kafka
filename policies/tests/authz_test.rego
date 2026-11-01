package governance.core.authz_test

import data.governance.core.authz
import data.governance.fixtures as f

ids(vs) := {v.policyId | some v in vs}

binding(role) := {"principal": "sa-1", "resource": "topic:team-a.orders", "role": role}

test_admin_role_is_hard_deny if {
	vs := authz.violations with input as f.request("development", [], [binding("CloudClusterAdmin")])
	some v in vs
	v.policyId == "RBAC_ADMIN_ROLE_FORBIDDEN"
	v.severity == "HARD_DENY"
	v.bypassable == false
}

test_admin_role_hard_deny_at_plan_stage if {
	vs := authz.violations with input as f.plan("development", [f.action("RBAC_ASSIGN", "RBAC", "sa#r#OrganizationAdmin", {"role": "OrganizationAdmin"})])
	"RBAC_ADMIN_ROLE_FORBIDDEN" in ids(vs)
}

test_pool_scoped_bindings_are_checked if {
	base := f.request("development", [], [])
	inp := object.union(base, {"manifest": object.union(base.manifest, {"identityPools": [{"name": "p", "principals": [], "rbac": [binding("EnvironmentAdmin")]}]})})
	"RBAC_ADMIN_ROLE_FORBIDDEN" in ids(authz.violations) with input as inp
}
