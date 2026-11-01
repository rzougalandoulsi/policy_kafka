package governance.quotas

import data.governance.quotas.lib as h

# Team-level quotas over projected usage (input contract 2.0). Governance supplies usage.current (confirmed state of
# every repository of the team in this environment), usage.reserved (other authorised plans still in flight) and
# usage.delta (what this request adds: the declared manifest at stage REQUEST, the planned actions at ACTION_PLAN).
# Admission is serialised per team by Governance (row lock on the team configuration) so two concurrent plans cannot
# both pass this check against the same free capacity.

# TEAM_QUOTA_EXCEEDED: projected usage of a quota key must not exceed team.quotas[key] (bypassable).
violations contains h.violation("TEAM_QUOTA_EXCEEDED", "DENY", msg, "QUOTA", key, true) if {
	some key, quota in object.get(input.team, "quotas", {})
	projected := h.projected(key)
	projected > quota
	msg := sprintf("team '%s' would use %v %s but its quota is %v (%v confirmed, %v reserved by other plans, %v from this change)", [
		h.team_id, projected, key, quota,
		h.usage_value("current", key), h.usage_value("reserved", key), h.usage_value("delta", key),
	])
}

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

contract_version := "2.0"

approvals := set()

catalog := [
	{"policyId": "TEAM_QUOTA_EXCEEDED", "bundle": "quotas", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST", "ACTION_PLAN"], "resourceType": "QUOTA", "description": "Projected team usage (confirmed + reserved + this change) must stay within the team's quotas (team configuration)."},
	{"policyId": "MAX_PARTITIONS_PER_PLAN", "bundle": "quotas", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "PLAN", "description": "One plan may create at most limits.maxPartitionsPerPlan partitions in total."},
]
