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

test_authorized_prefix_required if {
	vs := naming.violations with input as f.request("development", [f.topic("orders.events", 6)], [])
	"AUTHORIZED_PREFIX_REQUIRED" in ids(vs)
	some v in vs
	v.bypassable == true
	v.enforcement == "enforce"
	contains(v.message, "team-a.")
}

test_any_authorized_prefix_matches if {
	inp := f.with_team(f.request("development", [f.topic("orders.events", 6), f.topic("payments.x", 1)], []), {"authorizedPrefixes": ["team-a.", "orders.", "payments."]})
	count(naming.violations) == 0 with input as inp
}

test_no_authorized_prefix_matches if {
	inp := f.with_team(f.request("development", [f.topic("billing.events", 6)], []), {"authorizedPrefixes": ["team-a.", "orders."]})
	vs := naming.violations with input as inp
	ids(vs) == {"AUTHORIZED_PREFIX_REQUIRED"}
	some v in vs
	contains(v.message, "team-a., orders.")
}

test_prefix_match_is_case_insensitive if {
	inp := f.with_team(f.request("development", [f.topic("orders.events", 6)], []), {"authorizedPrefixes": ["ORDERS."]})
	not "AUTHORIZED_PREFIX_REQUIRED" in ids(naming.violations) with input as inp
}

test_empty_prefix_list_denies_every_topic if {
	inp := f.with_team(f.request("development", [f.topic("team-a.orders", 6)], []), {"authorizedPrefixes": []})
	vs := naming.violations with input as inp
	"AUTHORIZED_PREFIX_REQUIRED" in ids(vs)
	some v in vs
	contains(v.message, "none configured")
}

test_prefix_rule_disabled_by_configuration if {
	inp := f.with_config(f.request("development", [f.topic("orders.events", 6)], []), "naming", {"requireTeamPrefix": false})
	not "AUTHORIZED_PREFIX_REQUIRED" in ids(naming.violations) with input as inp
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

test_shadow_mode_is_reported_on_the_violation if {
	inp := f.with_enforcement(f.request("development", [f.topic("orders.events", 6)], []), {"AUTHORIZED_PREFIX_REQUIRED": "shadow"})
	vs := naming.violations with input as inp
	some v in vs
	v.policyId == "AUTHORIZED_PREFIX_REQUIRED"
	v.enforcement == "shadow"
}
