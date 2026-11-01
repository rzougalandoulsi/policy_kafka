package governance.fixtures

# Shared fixtures for policy tests (file is excluded from the bundle by build.sh). Shapes follow contracts/opa/input.schema.json (2.0).

config := {
	"naming": {
		"topicPattern": "^[a-z][a-z0-9]*([.-][a-z0-9]+)*$",
		"requireTeamPrefix": true,
		"teamPrefixSeparator": ".",
		"environmentRestrictions": {"production": {"forbiddenPatterns": ["^[a-z0-9-]+\\.(tmp|test|scratch)(\\.|$)"]}},
	},
	"rbac": {
		"allowedRoles": ["DeveloperRead", "DeveloperWrite", "DeveloperManage", "ResourceOwner"],
		"forbiddenRoles": ["CloudClusterAdmin", "EnvironmentAdmin", "OrganizationAdmin"],
		"environmentRoles": {"production": ["DeveloperRead", "DeveloperWrite", "DeveloperManage"]},
		"teamRoles": {"team-locked": ["DeveloperRead"]},
		"sensitiveRoles": ["DeveloperManage", "ResourceOwner"],
	},
	"limits": {
		"maxTopicsPerManifest": 3,
		"maxPartitionsPerTopic": 64,
		"maxPartitionsPerPlan": 100,
		"maxRetentionMs": 2592000000,
		"environmentOverrides": {"development": {"maxPartitionsPerTopic": 12}},
	},
	"destructive": {
		"topicDeleteAllowedEnvironments": ["development", "staging", "production"],
		"rbacRevokeAllowedEnvironments": ["development", "staging"],
		"identityPoolDeleteAllowedEnvironments": ["development"],
	},
	"approvals": {
		"productionTopicDeletion": true,
		"highImpactPartitionThreshold": 50,
		"sensitiveRoleAssignment": true,
		"sensitiveEnvironments": ["production"],
	},
}

team := {
	"id": "team-a",
	"authorizedPrefixes": ["team-a."],
	"quotas": {},
	"allowedRoles": [],
	"environments": {},
	"configurationVersion": 0,
	"source": "default",
}

zero := {"topics": 0, "topicPartitions": 0, "identityPools": 0, "rbacBindings": 0}

usage(current, reserved, delta) := {
	"current": object.union(zero, current),
	"reserved": object.union(zero, reserved),
	"delta": object.union(zero, delta),
	"byRepository": {},
	"asOfStateVersions": {},
}

manifest(topics, rbac) := {
	"teamId": "team-a",
	"repositoryId": "repo-1",
	"environment": "development",
	"topics": topics,
	"rbac": rbac,
	"identityPools": [],
}

topic(name, partitions) := {"name": name, "partitions": partitions, "retentionMs": null, "config": {}}

request(env, topics, rbac) := {
	"contractVersion": "2.0",
	"stage": "REQUEST",
	"now": "2026-11-01T10:00:00Z",
	"principal": {"id": "user-1", "roles": []},
	"team": team,
	"repository": {"id": "repo-1", "ownedByTeam": true},
	"environment": env,
	"manifest": object.union(manifest(topics, rbac), {"environment": env}),
	"actions": [],
	"plan": {},
	"usage": usage({}, {}, {}),
	"ownership": {"resources": {}},
	"request": {"requestId": "req-1", "manifestDigest": "m"},
	"configuration": config,
	"exceptions": [],
}

plan(env, actions) := object.union(request(env, [], []), {
	"stage": "ACTION_PLAN",
	"actions": actions,
	"plan": {"planId": "plan-1", "planDigest": "d", "stateVersion": 0},
})

action(type, rtype, name, payload) := {"actionId": "a", "resourceType": rtype, "resourceName": name, "actionType": type, "payload": payload}

# Overrides one top-level section of an input (team, usage, ownership, configuration, ...).
with_section(inp, name, value) := object.union(inp, {name: value})

with_team(inp, patch) := object.union(inp, {"team": object.union(inp.team, patch)})

with_config(inp, section, patch) := object.union(inp, {"configuration": object.union(inp.configuration, {section: object.union(inp.configuration[section], patch)})})

with_enforcement(inp, modes) := object.union(inp, {"configuration": object.union(inp.configuration, {"enforcement": modes})})
