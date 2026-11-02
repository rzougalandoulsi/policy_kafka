package governance.changes.destructive

import data.governance.changes.lib as h

# Action-plan stage only: destructive operations must be enabled for the environment (bypassable).

violations contains h.violation("TOPIC_DELETE_RESTRICTED", "DENY", msg, "TOPIC", a.resourceName, true) if {
	h.stage_plan
	not h.env in h.config.destructive.topicDeleteAllowedEnvironments
	some a in input.actions
	a.actionType == "TOPIC_DELETE"
	msg := sprintf("deleting topic '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}

violations contains h.violation("RBAC_REVOKE_RESTRICTED", "DENY", msg, "RBAC", a.resourceName, true) if {
	h.stage_plan
	not h.env in h.config.destructive.rbacRevokeAllowedEnvironments
	some a in input.actions
	a.actionType == "RBAC_REVOKE"
	msg := sprintf("revoking binding '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}

violations contains h.violation("IDENTITY_POOL_DELETE_RESTRICTED", "DENY", msg, "IDENTITY_POOL", a.resourceName, true) if {
	h.stage_plan
	not h.env in h.config.destructive.identityPoolDeleteAllowedEnvironments
	some a in input.actions
	a.actionType == "IDENTITY_POOL_DELETE"
	msg := sprintf("deleting identity pool '%s' is not allowed in environment '%s'", [a.resourceName, h.env])
}
