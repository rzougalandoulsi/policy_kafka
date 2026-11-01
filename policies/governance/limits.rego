package governance.limits

import data.governance.helpers as h

# MAX_TOPICS_PER_MANIFEST (bypassable)
violations contains h.violation("MAX_TOPICS_PER_MANIFEST", "DENY", msg, "MANIFEST", input.manifest.repositoryId, true) if {
	h.stage_request
	count(input.manifest.topics) > h.limit("maxTopicsPerManifest")
	msg := sprintf("manifest declares %d topics, maximum is %d", [count(input.manifest.topics), h.limit("maxTopicsPerManifest")])
}

# MAX_PARTITIONS_PER_TOPIC (bypassable)
violations contains h.violation("MAX_PARTITIONS_PER_TOPIC", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	t.partitions > h.limit("maxPartitionsPerTopic")
	msg := sprintf("topic '%s' requests %d partitions, maximum is %d", [t.name, t.partitions, h.limit("maxPartitionsPerTopic")])
}

# MAX_PARTITIONS_PER_PLAN: sum of partitions created by one plan (bypassable)
violations contains h.violation("MAX_PARTITIONS_PER_PLAN", "DENY", msg, "PLAN", input.plan.planId, true) if {
	h.stage_plan
	total := sum([p |
		some a in input.actions
		a.actionType == "TOPIC_CREATE"
		p := to_number(object.get(a.payload, "partitions", "0"))
	])
	total > h.limit("maxPartitionsPerPlan")
	msg := sprintf("plan creates %d partitions, maximum is %d", [total, h.limit("maxPartitionsPerPlan")])
}

# TOPIC_RETENTION_LIMIT: retention.ms must not exceed the limit; -1 (infinite) is never allowed (bypassable)
violations contains h.violation("TOPIC_RETENTION_LIMIT", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request
	some t in input.manifest.topics
	retention := effective_retention(t)
	retention_exceeds(retention)
	msg := sprintf("topic '%s' retention %v ms exceeds limit %d ms", [t.name, retention, h.limit("maxRetentionMs")])
}

effective_retention(t) := r if {
	r := t.retentionMs
	r != null
} else := r if {
	r := to_number(t.config["retention.ms"])
}

retention_exceeds(r) if r == -1

retention_exceeds(r) if r > h.limit("maxRetentionMs")
