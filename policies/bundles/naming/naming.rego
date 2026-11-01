package governance.naming

import data.governance.naming.lib as h

# TOPIC_NAME_PATTERN: every topic name must match the configured pattern (bypassable).
violations contains h.violation("TOPIC_NAME_PATTERN", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	not regex.match(h.config.naming.topicPattern, t.name)
	msg := sprintf("topic '%s' does not match pattern %s", [t.name, h.config.naming.topicPattern])
}

# AUTHORIZED_PREFIX_REQUIRED: topics must start with one of the team's authorised prefixes
# (input.team.authorizedPrefixes, case-insensitive; bypassable). Replaces TEAM_PREFIX_REQUIRED of contract 1.0.
violations contains h.violation("AUTHORIZED_PREFIX_REQUIRED", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	h.config.naming.requireTeamPrefix == true
	some t in input.manifest.topics
	not h.has_authorized_prefix(t.name)
	msg := sprintf("topic '%s' must start with an authorised prefix of team '%s' (%s)", [t.name, h.team_id, prefix_list])
}

prefix_list := "none configured" if count(h.team_prefixes) == 0

prefix_list := concat(", ", h.team_prefixes) if count(h.team_prefixes) > 0

# ENV_NAME_RESTRICTION: environment-specific forbidden name patterns (bypassable).
violations contains h.violation("ENV_NAME_RESTRICTION", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	some pattern in h.config.naming.environmentRestrictions[h.env].forbiddenPatterns
	regex.match(pattern, t.name)
	msg := sprintf("topic '%s' is not allowed in environment '%s' (matches %s)", [t.name, h.env, pattern])
}

# ---- bundle contract

contract_version := "2.0"

approvals := set()

catalog := [
	{"policyId": "TOPIC_NAME_PATTERN", "bundle": "naming", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topic names must match configuration naming.topicPattern."},
	{"policyId": "AUTHORIZED_PREFIX_REQUIRED", "bundle": "naming", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topics must start with one of the team's authorised prefixes (team configuration) when naming.requireTeamPrefix is true."},
	{"policyId": "ENV_NAME_RESTRICTION", "bundle": "naming", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topic names must not match naming.environmentRestrictions[env].forbiddenPatterns."},
]
