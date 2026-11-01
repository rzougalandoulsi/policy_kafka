package governance.resources.rbac_test

import data.governance.fixtures as f
import data.governance.resources.rbac

ids(vs) := {v.policyId | some v in vs}

binding(role) := {"principal": "sa-1", "resource": "topic:team-a.orders", "role": role}

test_allowed_role_passes if {
	count(rbac.violations) == 0 with input as f.request("development", [], [binding("DeveloperRead")])
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
	inp := f.with_team(f.request("development", [], [binding("DeveloperWrite")]), {"id": "team-locked"})
	"RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as inp
	not "RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as f.request("development", [], [binding("DeveloperWrite")])
}

test_team_configuration_allowed_roles_override_configuration if {
	locked := f.with_team(f.request("development", [], [binding("DeveloperWrite")]), {"id": "team-locked", "allowedRoles": ["DeveloperWrite"]})
	not "RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as locked
	restricted := f.with_team(f.request("development", [], [binding("DeveloperWrite")]), {"allowedRoles": ["DeveloperRead"]})
	"RBAC_TEAM_ROLE_ALLOWED" in ids(rbac.violations) with input as restricted
}
