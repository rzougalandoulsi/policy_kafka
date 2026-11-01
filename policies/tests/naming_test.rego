package governance.naming_test

import data.governance.fixtures as f
import data.governance.naming

ids(vs) := {v.policyId | some v in vs}

test_valid_name_passes if {
	count(naming.violations) == 0 with input as f.request("development", [f.topic("team-a.orders", 6)], [])
}

test_pattern_violation if {
	"TOPIC_NAME_PATTERN" in ids(naming.violations) with input as f.request("development", [f.topic("Team-A.Orders", 6)], [])
}

test_team_prefix_required if {
	vs := naming.violations with input as f.request("development", [f.topic("orders.events", 6)], [])
	"TEAM_PREFIX_REQUIRED" in ids(vs)
	some v in vs
	v.bypassable == true
}

test_team_prefix_disabled if {
	cfg := object.union(f.config, {"naming": object.union(f.config.naming, {"requireTeamPrefix": false})})
	inp := object.union(f.request("development", [f.topic("orders.events", 6)], []), {"configuration": cfg})
	not "TEAM_PREFIX_REQUIRED" in ids(naming.violations) with input as inp
}

test_env_restriction_in_production if {
	"ENV_NAME_RESTRICTION" in ids(naming.violations) with input as f.request("production", [f.topic("team-a.tmp.orders", 6)], [])
}

test_env_restriction_not_in_development if {
	not "ENV_NAME_RESTRICTION" in ids(naming.violations) with input as f.request("development", [f.topic("team-a.tmp.orders", 6)], [])
}

test_naming_not_evaluated_at_plan_stage if {
	count(naming.violations) == 0 with input as f.plan("development", [f.action("TOPIC_CREATE", "TOPIC", "Bad Name", {})])
}
