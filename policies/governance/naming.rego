package governance.naming

import data.governance.helpers as h

# TOPIC_NAME_PATTERN: every topic name must match the configured pattern (bypassable).
violations contains h.violation("TOPIC_NAME_PATTERN", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	not regex.match(h.config.naming.topicPattern, t.name)
	msg := sprintf("topic '%s' does not match pattern %s", [t.name, h.config.naming.topicPattern])
}

# TEAM_PREFIX_REQUIRED: topics must be prefixed with the owning team id (bypassable).
violations contains h.violation("TEAM_PREFIX_REQUIRED", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	h.config.naming.requireTeamPrefix == true
	some t in input.manifest.topics
	not startswith(lower(t.name), h.team_prefix)
	msg := sprintf("topic '%s' must start with team prefix '%s'", [t.name, h.team_prefix])
}

# ENV_NAME_RESTRICTION: environment-specific forbidden name patterns (bypassable).
violations contains h.violation("ENV_NAME_RESTRICTION", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	some pattern in h.config.naming.environmentRestrictions[h.env].forbiddenPatterns
	regex.match(pattern, t.name)
	msg := sprintf("topic '%s' is not allowed in environment '%s' (matches %s)", [t.name, h.env, pattern])
}
