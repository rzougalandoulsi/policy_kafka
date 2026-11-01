package governance.approval

import data.governance.helpers as h

# Approval requirements never deny by themselves; they are ignored when a hard deny applies (see decision.rego).

# PROD_TOPIC_DELETE_REQUIRES_APPROVAL
approvals contains h.approval("PROD_TOPIC_DELETE_REQUIRES_APPROVAL", msg, "TOPIC", a.resourceName) if {
	h.stage_plan
	h.config.approvals.productionTopicDeletion == true
	h.env in sensitive_envs
	some a in input.actions
	a.actionType == "TOPIC_DELETE"
	msg := sprintf("deleting topic '%s' in %s requires approval", [a.resourceName, h.env])
}

# HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL: a plan creating many partitions at once
approvals contains h.approval("HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL", msg, "PLAN", input.plan.planId) if {
	h.stage_plan
	total := sum([p |
		some a in input.actions
		a.actionType == "TOPIC_CREATE"
		p := to_number(object.get(a.payload, "partitions", "0"))
	])
	total >= h.config.approvals.highImpactPartitionThreshold
	msg := sprintf("plan creates %d partitions (threshold %d) and requires approval", [total, h.config.approvals.highImpactPartitionThreshold])
}

# SENSITIVE_PERMISSION_REQUIRES_APPROVAL: sensitive roles in sensitive environments (manifest stage)
approvals contains h.approval("SENSITIVE_PERMISSION_REQUIRES_APPROVAL", msg, "RBAC", key(b)) if {
	h.stage_request
	h.config.approvals.sensitiveRoleAssignment == true
	h.env in sensitive_envs
	some b in h.bindings
	b.role in h.config.rbac.sensitiveRoles
	msg := sprintf("granting sensitive role '%s' in %s requires approval", [b.role, h.env])
}

sensitive_envs := object.get(h.config.approvals, "sensitiveEnvironments", ["production"])

key(b) := sprintf("%s#%s#%s", [b.principal, b.resource, b.role])
