package governance.changes.lib

# Shared helpers (Rego v1). Every bundle carries an identical copy under its own root because a bundle must
# compile on its own (cross-bundle function calls do not compile). scripts/check-lib-sync.sh keeps the copies equal.

stage_request if input.stage == "REQUEST"

stage_plan if input.stage == "ACTION_PLAN"

config := input.configuration

env := input.environment

team_id := input.team.id

# Lower-cased team prefix used by the naming policy, e.g. "team-a" -> "team-a."
team_prefix := concat("", [lower(team_id), object.get(config.naming, "teamPrefixSeparator", ".")])

# Topic names affected by a plan (create/update/delete) for action-level rules.
plan_topics contains a.resourceName if {
	some a in input.actions
	a.resourceType == "TOPIC"
}

# Effective limit with per-environment override.
limit(name) := value if {
	value := config.limits.environmentOverrides[env][name]
} else := value if {
	value := config.limits[name]
}

# All RBAC bindings of the manifest (standalone and pool-scoped).
bindings contains b if {
	some b in input.manifest.rbac
}

bindings contains b if {
	some p in input.manifest.identityPools
	some b in p.rbac
}

# Standard violation object. `bypassable` decides whether a scoped exception may exempt it.
violation(policy_id, severity, msg, rtype, rname, bypassable) := {
	"policyId": policy_id,
	"severity": severity,
	"message": msg,
	"resourceType": rtype,
	"resourceName": rname,
	"bypassable": bypassable,
}

approval(policy_id, msg, rtype, rname) := {
	"policyId": policy_id,
	"message": msg,
	"resourceType": rtype,
	"resourceName": rname,
}
