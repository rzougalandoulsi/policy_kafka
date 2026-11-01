package governance.core.authz

import data.governance.core.lib as h

# Global prohibitions: policies that no team, exception or approval can override. Evaluated at both stages.

# RBAC_ADMIN_ROLE_FORBIDDEN: administrative roles can never be requested. HARD_DENY: no exception, no approval.
violations contains h.violation("RBAC_ADMIN_ROLE_FORBIDDEN", "HARD_DENY", msg, "RBAC", key(b), false) if {
	some b in h.bindings
	b.role in h.config.rbac.forbiddenRoles
	msg := sprintf("role '%s' is an administrative role and can never be granted through manifests", [b.role])
}

# Plan-stage counterpart: an RBAC_ASSIGN action carrying a forbidden role (defense in depth).
violations contains h.violation("RBAC_ADMIN_ROLE_FORBIDDEN", "HARD_DENY", msg, "RBAC", a.resourceName, false) if {
	h.stage_plan
	some a in input.actions
	a.actionType == "RBAC_ASSIGN"
	a.payload.role in h.config.rbac.forbiddenRoles
	msg := sprintf("action assigns forbidden administrative role '%s'", [a.payload.role])
}

key(b) := sprintf("%s#%s#%s", [b.principal, b.resource, b.role])

catalog := [{"policyId": "RBAC_ADMIN_ROLE_FORBIDDEN", "bundle": "core", "kind": "VIOLATION", "section": "rbac", "severity": "HARD_DENY", "bypassable": false, "stages": ["REQUEST", "ACTION_PLAN"], "resourceType": "RBAC", "description": "Administrative roles (rbac.forbiddenRoles) can never be granted; no exception and no approval applies."}]
