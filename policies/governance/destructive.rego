package governance.destructive

import data.governance.helpers as h

# Action-plan stage only: destructive operations must be enabled for the environment (bypassable).

violations contains h.violation("TOPIC_DELETE_RESTRICTED", "DENY", msg, "TOPIC", a.resourceName, true) if {
	h.stage_plan
	some a in input.actions
	a.actionType == "TOPIC_DELETE"
	not h.env in h.config.destructive.topicDeleteAllowedEnvironments
	msg := sprintf("deleting topic '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}

violations contains h.violation("RBAC_REVOKE_RESTRICTED", "DENY", msg, "RBAC", a.resourceName, true) if {
	h.stage_plan
	some a in input.actions
	a.actionType == "RBAC_REVOKE"
	not h.env in h.config.destructive.rbacRevokeAllowedEnvironments
	msg := sprintf("revoking binding '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}

violations contains h.violation("IDENTITY_POOL_DELETE_RESTRICTED", "DENY", msg, "IDENTITY_POOL", a.resourceName, true) if {
	h.stage_plan
	some a in input.actions
	a.actionType == "IDENTITY_POOL_DELETE"
	not h.env in h.config.destructive.identityPoolDeleteAllowedEnvironments
	msg := sprintf("deleting identity pool '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}
