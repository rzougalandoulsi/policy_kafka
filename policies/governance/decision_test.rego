package governance.decision_test

import data.governance
import data.governance.fixtures as f

exception(policy, pattern, actions) := {
	"id": "exc-1", "policyId": policy, "principalId": "*", "teamId": "team-a",
	"environment": "*", "resourcePattern": pattern, "actionTypes": actions,
}

test_allow_clean_manifest if {
	d := governance.decision with input as f.request("development", [f.topic("team-a.orders", 6)], [])
	d.decision == "ALLOW"
	count(d.violations) == 0
	count(d.approvals) == 0
}

test_deny_on_violation if {
	d := governance.decision with input as f.request("development", [f.topic("orders", 6)], [])
	d.decision == "DENY"
	"TEAM_PREFIX_REQUIRED" in d.matchedPolicies
}

test_exception_exempts_bypassable_violation if {
	inp := object.union(f.request("development", [f.topic("orders", 6)], []), {"exceptions": [exception("TEAM_PREFIX_REQUIRED", "orders*", [])]})
	d := governance.decision with input as inp
	d.decision == "ALLOW"
	some v in d.violations
	v.exempted == true
	v.exceptionId == "exc-1"
	d.appliedExceptions == ["exc-1"]
}

test_exception_resource_scope_must_match if {
	inp := object.union(f.request("development", [f.topic("orders", 6)], []), {"exceptions": [exception("TEAM_PREFIX_REQUIRED", "payments*", [])]})
	d := governance.decision with input as inp
	d.decision == "DENY"
}

test_exception_principal_scope if {
	e := object.union(exception("TEAM_PREFIX_REQUIRED", "*", []), {"principalId": "someone-else"})
	inp := object.union(f.request("development", [f.topic("orders", 6)], []), {"exceptions": [e]})
	d := governance.decision with input as inp
	d.decision == "DENY"
}

test_exception_never_exempts_hard_deny if {
	b := {"principal": "sa", "resource": "r", "role": "CloudClusterAdmin"}
	inp := object.union(f.request("development", [], [b]), {"exceptions": [exception("RBAC_ADMIN_ROLE_FORBIDDEN", "*", [])]})
	d := governance.decision with input as inp
	d.decision == "DENY"
	count(d.appliedExceptions) == 0
}

test_hard_deny_suppresses_approvals if {
	b := {"principal": "sa", "resource": "r", "role": "OrganizationAdmin"}
	s := {"principal": "sa", "resource": "r", "role": "DeveloperManage"}
	d := governance.decision with input as f.request("production", [], [b, s])
	d.decision == "DENY"
	count(d.approvals) == 0
}

test_require_approval_when_no_violation if {
	d := governance.decision with input as f.plan("production", [f.action("TOPIC_DELETE", "TOPIC", "team-a.old", {})])
	d.decision == "REQUIRE_APPROVAL"
	"PROD_TOPIC_DELETE_REQUIRES_APPROVAL" in d.matchedPolicies
}

test_deny_takes_precedence_over_approval if {
	actions := [f.action("RBAC_REVOKE", "RBAC", "sa#r#DeveloperRead", {}), f.action("TOPIC_DELETE", "TOPIC", "team-a.old", {})]
	d := governance.decision with input as f.plan("production", actions)
	d.decision == "DENY"
	count(d.approvals) > 0
}

test_action_scoped_exception_at_plan_stage if {
	e := exception("RBAC_REVOKE_RESTRICTED", "*", ["RBAC_REVOKE"])
	inp := object.union(f.plan("production", [f.action("RBAC_REVOKE", "RBAC", "sa#r#DeveloperRead", {})]), {"exceptions": [e]})
	d := governance.decision with input as inp
	d.decision == "ALLOW"
	wrong := exception("RBAC_REVOKE_RESTRICTED", "*", ["TOPIC_DELETE"])
	inp2 := object.union(inp, {"exceptions": [wrong]})
	d2 := governance.decision with input as inp2
	d2.decision == "DENY"
}

test_output_is_deterministically_ordered if {
	topics := [f.topic("zeta", 6), f.topic("alpha", 6)]
	d := governance.decision with input as f.request("development", topics, [])
	names := [v.resourceName | some v in d.violations]
	names == sort(names)
}
