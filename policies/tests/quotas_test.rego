package governance.quotas_test

import data.governance.fixtures as f
import data.governance.quotas

ids(vs) := {v.policyId | some v in vs}

test_plan_partition_budget if {
	actions := [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "60"}), f.action("TOPIC_CREATE", "TOPIC", "team-a.b", {"partitions": "60"})]
	"MAX_PARTITIONS_PER_PLAN" in ids(quotas.violations) with input as f.plan("development", actions)
	not "MAX_PARTITIONS_PER_PLAN" in ids(quotas.violations) with input as f.plan("development", [actions[0]])
}
