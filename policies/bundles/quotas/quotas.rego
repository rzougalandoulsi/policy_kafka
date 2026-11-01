package governance.quotas

import data.governance.quotas.lib as h

# Team-level quotas. Phase 1 carries the plan-level partition budget; projected team usage arrives with input contract 2.0.

# MAX_PARTITIONS_PER_PLAN: sum of partitions created by one plan (bypassable)
violations contains h.violation("MAX_PARTITIONS_PER_PLAN", "DENY", msg, "PLAN", input.plan.planId, true) if {
	h.stage_plan
	total := sum([p |
		some a in input.actions
		a.actionType == "TOPIC_CREATE"
		p := to_number(object.get(a.payload, "partitions", "0"))
	])
	total > h.limit("maxPartitionsPerPlan")
	msg := sprintf("plan creates %d partitions, maximum is %d", [total, h.limit("maxPartitionsPerPlan")])
}

# ---- bundle contract

contract_version := "1.0"

approvals := set()

catalog := [
	{"policyId": "MAX_PARTITIONS_PER_PLAN", "bundle": "quotas", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "PLAN", "description": "One plan may create at most limits.maxPartitionsPerPlan partitions in total."},
]
