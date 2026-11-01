package governance.resources.rbac

import data.governance.resources.lib as h

# RBAC_ROLE_ALLOWED: only roles on the allow-list (bypassable).
violations contains h.violation("RBAC_ROLE_ALLOWED", "DENY", msg, "RBAC", key(b), true) if {
	h.stage_request
	some b in h.bindings
	not b.role in h.config.rbac.forbiddenRoles
	not b.role in h.config.rbac.allowedRoles
	msg := sprintf("role '%s' is not an authorized role", [b.role])
}

# RBAC_ENV_RESTRICTED: environment-specific role restrictions (bypassable).
violations contains h.violation("RBAC_ENV_RESTRICTED", "DENY", msg, "RBAC", key(b), true) if {
	h.stage_request
	some b in h.bindings
	allowed := h.config.rbac.environmentRoles[h.env]
	not b.role in allowed
	not b.role in h.config.rbac.forbiddenRoles
	msg := sprintf("role '%s' is not permitted in environment '%s'", [b.role, h.env])
}

# RBAC_TEAM_ROLE_ALLOWED: team-specific role permissions when configured (bypassable).
violations contains h.violation("RBAC_TEAM_ROLE_ALLOWED", "DENY", msg, "RBAC", key(b), true) if {
	h.stage_request
	some b in h.bindings
	allowed := h.config.rbac.teamRoles[h.team_id]
	not b.role in allowed
	not b.role in h.config.rbac.forbiddenRoles
	msg := sprintf("team '%s' may not grant role '%s'", [h.team_id, b.role])
}

key(b) := sprintf("%s#%s#%s", [b.principal, b.resource, b.role])
