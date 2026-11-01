package governance.limits_test

import data.governance.fixtures as f
import data.governance.limits

ids(vs) := {v.policyId | some v in vs}

test_within_limits if {
	count(limits.violations) == 0 with input as f.request("development", [f.topic("team-a.a", 6)], [])
}

test_too_many_topics if {
	topics := [f.topic("team-a.a", 1), f.topic("team-a.b", 1), f.topic("team-a.c", 1), f.topic("team-a.d", 1)]
	"MAX_TOPICS_PER_MANIFEST" in ids(limits.violations) with input as f.request("development", topics, [])
}

test_partitions_env_override if {
	"MAX_PARTITIONS_PER_TOPIC" in ids(limits.violations) with input as f.request("development", [f.topic("team-a.a", 24)], [])
	not "MAX_PARTITIONS_PER_TOPIC" in ids(limits.violations) with input as f.request("staging", [f.topic("team-a.a", 24)], [])
	"MAX_PARTITIONS_PER_TOPIC" in ids(limits.violations) with input as f.request("staging", [f.topic("team-a.a", 65)], [])
}

test_retention_limit_from_field if {
	t := object.union(f.topic("team-a.a", 1), {"retentionMs": 9999999999999})
	"TOPIC_RETENTION_LIMIT" in ids(limits.violations) with input as f.request("development", [t], [])
}

test_retention_limit_from_config_and_infinite if {
	t := object.union(f.topic("team-a.a", 1), {"config": {"retention.ms": "-1"}})
	"TOPIC_RETENTION_LIMIT" in ids(limits.violations) with input as f.request("development", [t], [])
	ok := object.union(f.topic("team-a.a", 1), {"config": {"retention.ms": "86400000"}})
	not "TOPIC_RETENTION_LIMIT" in ids(limits.violations) with input as f.request("development", [ok], [])
}

test_plan_partition_budget if {
	actions := [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "60"}), f.action("TOPIC_CREATE", "TOPIC", "team-a.b", {"partitions": "60"})]
	"MAX_PARTITIONS_PER_PLAN" in ids(limits.violations) with input as f.plan("development", actions)
	not "MAX_PARTITIONS_PER_PLAN" in ids(limits.violations) with input as f.plan("development", [actions[0]])
}
