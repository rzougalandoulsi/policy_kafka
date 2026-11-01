package governance.changes.lib

# Shared helpers (Rego v1). Every bundle carries an identical copy under its own root because a bundle must
# compile on its own (cross-bundle function calls do not compile). scripts/check-lib-sync.sh keeps the copies equal.

stage_request if input.stage == "REQUEST"

stage_plan if input.stage == "ACTION_PLAN"

config := input.configuration

env := input.environment

team_id := input.team.id

# Authorised topic prefixes of the team (input contract 2.0, lower-cased). Governance defaults to "<teamId><separator>"
# for teams without a stored configuration, so an empty list means "no prefix is authorised".
team_prefixes := [lower(p) | some p in object.get(input.team, "authorizedPrefixes", [])]

has_authorized_prefix(name) if {
	some p in team_prefixes
	startswith(lower(name), p)
}

# Enforcement mode of a policy from configuration.enforcement: "enforce" (default) or "shadow" (recorded, never blocking).
enforcement(policy_id) := object.get(config, ["enforcement", policy_id], "enforce")

# Usage counters supplied by Governance (input.usage.{current,reserved,delta}[key]); missing keys count as 0.
usage_value(section, key) := to_number(object.get(input, ["usage", section, key], 0))

# Projected team usage for one quota key: confirmed + reserved by other in-flight plans + what this request adds.
projected(key) := (usage_value("current", key) + usage_value("reserved", key)) + usage_value("delta", key)

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

# Standard violation object. `bypassable` decides whether a scoped exception may exempt it; `enforcement` whether it
# blocks ("enforce") or is only recorded ("shadow").
violation(policy_id, severity, msg, rtype, rname, bypassable) := {
	"policyId": policy_id,
	"severity": severity,
	"message": msg,
	"resourceType": rtype,
	"resourceName": rname,
	"bypassable": bypassable,
	"enforcement": enforcement(policy_id),
}

approval(policy_id, msg, rtype, rname) := {
	"policyId": policy_id,
	"message": msg,
	"resourceType": rtype,
	"resourceName": rname,
	"enforcement": enforcement(policy_id),
}
