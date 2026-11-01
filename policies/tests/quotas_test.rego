package governance.quotas_test

import data.governance.fixtures as f
import data.governance.quotas

ids(vs) := {v.policyId | some v in vs}

quota_input(quotas, current, reserved, delta) := f.with_section(f.with_team(f.request("development", [], []), {"quotas": quotas}), "usage", f.usage(current, reserved, delta))

test_no_quota_means_unlimited if {
	count(quotas.violations) == 0 with input as quota_input({}, {"topics": 900}, {}, {"topics": 100})
}

test_projected_within_quota if {
	count(quotas.violations) == 0 with input as quota_input({"topics": 5}, {"topics": 3}, {}, {"topics": 2})
}

# Two repositories of one team: repo-2 holds 3 topics (current), this manifest adds 3 more, quota is 5.
test_two_repositories_cannot_jointly_exceed_quota if {
	vs := quotas.violations with input as quota_input({"topics": 5}, {"topics": 3}, {}, {"topics": 3})
	ids(vs) == {"TEAM_QUOTA_EXCEEDED"}
	some v in vs
	v.resourceName == "topics"
	v.bypassable == true
	contains(v.message, "would use 6 topics")
}

test_reservations_of_other_plans_count if {
	count(quotas.violations) == 0 with input as quota_input({"topicPartitions": 128}, {"topicPartitions": 0}, {}, {"topicPartitions": 128})
	"TEAM_QUOTA_EXCEEDED" in ids(quotas.violations) with input as quota_input({"topicPartitions": 128}, {"topicPartitions": 0}, {"topicPartitions": 128}, {"topicPartitions": 128})
}

test_negative_delta_frees_capacity if {
	count(quotas.violations) == 0 with input as quota_input({"topics": 5}, {"topics": 5}, {}, {"topics": -1})
}

test_each_exceeded_key_is_reported if {
	vs := quotas.violations with input as quota_input({"topics": 1, "rbacBindings": 1}, {"topics": 1, "rbacBindings": 1}, {}, {"topics": 1, "rbacBindings": 1})
	{v.resourceName | some v in vs} == {"topics", "rbacBindings"}
}

test_quota_applies_at_plan_stage_too if {
	inp := f.with_section(f.with_team(f.plan("development", [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "6"})]), {"quotas": {"topics": 1}}), "usage", f.usage({"topics": 1}, {}, {"topics": 1}))
	"TEAM_QUOTA_EXCEEDED" in ids(quotas.violations) with input as inp
}

test_plan_partition_budget if {
	actions := [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "60"}), f.action("TOPIC_CREATE", "TOPIC", "team-a.b", {"partitions": "60"})]
	"MAX_PARTITIONS_PER_PLAN" in ids(quotas.violations) with input as f.plan("development", actions)
	not "MAX_PARTITIONS_PER_PLAN" in ids(quotas.violations) with input as f.plan("development", [actions[0]])
}
