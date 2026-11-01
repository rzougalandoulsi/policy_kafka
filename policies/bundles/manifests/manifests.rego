package governance.manifests

import data.governance.manifests.lib as h

# Manifest governance: repository ownership and cross-repository resource conflicts (input contract 2.0).
# Governance derives input.ownership.resources from the confirmed state of every repository in the environment:
# "TYPE:name" -> owning repository. Only resources this request declares (or creates) are checked.

# RESOURCE_OWNED_BY_OTHER_REPOSITORY: a topic or identity pool of this manifest already belongs to another
# repository's confirmed state (bypassable: a scoped exception covers a planned migration between repositories).
violations contains h.violation("RESOURCE_OWNED_BY_OTHER_REPOSITORY", "DENY", msg, rtype, name, true) if {
	some key, owner in object.get(input, ["ownership", "resources"], {})
	key in declared
	[rtype, name] := split_key(key)
	msg := sprintf("%s '%s' is owned by repository '%s'; repository '%s' cannot declare it", [lower(rtype), name, owner, input.repository.id])
}

# REPOSITORY_OWNED_BY_OTHER_TEAM: confirmed state for this repository id exists under another team (bypassable, so
# legitimately shared repository ids can be allowed explicitly).
violations contains h.violation("REPOSITORY_OWNED_BY_OTHER_TEAM", "DENY", msg, "REPOSITORY", input.repository.id, true) if {
	h.stage_request
	input.repository.ownedByTeam == false
	msg := sprintf("repository '%s' has confirmed state under another team; team '%s' cannot manage it", [input.repository.id, h.team_id])
}

# Resources this request introduces: declared topics and pools at stage REQUEST, created ones at stage ACTION_PLAN.
declared contains sprintf("TOPIC:%s", [t.name]) if {
	h.stage_request
	some t in input.manifest.topics
}

declared contains sprintf("IDENTITY_POOL:%s", [p.name]) if {
	h.stage_request
	some p in input.manifest.identityPools
}

declared contains sprintf("%s:%s", [a.resourceType, a.resourceName]) if {
	h.stage_plan
	some a in input.actions
	a.actionType in {"TOPIC_CREATE", "IDENTITY_POOL_CREATE"}
}

split_key(key) := [rtype, name] if {
	idx := indexof(key, ":")
	rtype := substring(key, 0, idx)
	name := substring(key, idx + 1, -1)
}

# ---- bundle contract

contract_version := "2.0"

approvals := set()

catalog := [
	{"policyId": "RESOURCE_OWNED_BY_OTHER_REPOSITORY", "bundle": "manifests", "kind": "VIOLATION", "section": "manifests", "severity": "DENY", "bypassable": true, "stages": ["REQUEST", "ACTION_PLAN"], "resourceType": "TOPIC", "description": "A topic or identity pool already owned by another repository (confirmed state) cannot be declared again."},
	{"policyId": "REPOSITORY_OWNED_BY_OTHER_TEAM", "bundle": "manifests", "kind": "VIOLATION", "section": "manifests", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "REPOSITORY", "description": "A repository id whose confirmed state belongs to another team cannot be managed by this team."},
]
