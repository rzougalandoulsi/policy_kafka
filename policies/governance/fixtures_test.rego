package governance.fixtures

# Shared fixtures for policy tests (file is excluded from the bundle by build.sh).

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
	"stage": "REQUEST",
	"principal": {"id": "user-1", "roles": []},
	"team": {"id": "team-a"},
	"environment": env,
	"manifest": object.union(manifest(topics, rbac), {"environment": env}),
	"actions": [],
	"plan": {},
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
