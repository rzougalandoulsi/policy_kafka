package governance.core.catalog_test

import data.governance.core
import data.governance.fixtures as f

bundles := {"core", "manifests", "naming", "quotas", "resources", "changes"}

test_policy_ids_are_unique if {
	count(core.catalog_ids) == count(core.policy_catalog)
}

test_every_entry_names_its_bundle if {
	every p in core.policy_catalog {
		p.bundle in bundles
	}
}

test_hard_deny_is_never_bypassable if {
	count({p | some p in core.policy_catalog; p.severity == "HARD_DENY"; p.bypassable == true}) == 0
}

test_approvals_are_never_bypassable if {
	count({p | some p in core.policy_catalog; p.kind == "APPROVAL"; p.bypassable == true}) == 0
}

test_catalogue_has_the_expected_size if {
	count(core.policy_catalog) == 20
	count(core.bypassable_ids) == 16
}

test_every_section_is_a_configuration_section if {
	every p in core.policy_catalog {
		p.section in {"naming", "rbac", "limits", "destructive", "approvals", "manifests"}
	}
}

# Every violation and approval the composed decision emits against a worst-case input must be catalogued,
# with the same severity and bypassable flag.
test_emitted_violations_are_catalogued if {
	d := core.decision with input as worst_request
	count(d.violations) > 0
	every v in d.violations {
		some p in core.policy_catalog
		p.policyId == v.policyId
		p.severity == v.severity
		p.bypassable == v.bypassable
	}
}

test_emitted_plan_violations_and_approvals_are_catalogued if {
	d := core.decision with input as worst_plan
	count(d.violations) > 0
	every v in d.violations {
		some p in core.policy_catalog
		p.policyId == v.policyId
		p.severity == v.severity
		p.bypassable == v.bypassable
	}
	raw := data.governance.changes.approvals with input as worst_plan
	count(raw) > 0
	every a in raw {
		some p in core.policy_catalog
		p.policyId == a.policyId
		p.kind == "APPROVAL"
	}
}

worst_request := {
	"stage": "REQUEST",
	"principal": {"id": "dev-1", "roles": []},
	"team": object.union(f.team, {"id": "team-locked", "authorizedPrefixes": ["team-locked."], "quotas": {"topics": 1}}),
	"repository": {"id": "repo-1", "ownedByTeam": false},
	"usage": f.usage({"topics": 1}, {}, {"topics": 4}),
	"ownership": {"resources": {"TOPIC:other.one": "repo-9"}},
	"environment": "production",
	"configuration": f.config,
	"exceptions": [],
	"manifest": {
		"teamId": "team-locked",
		"repositoryId": "repo-1",
		"environment": "production",
		"topics": [
			{"name": "Bad Name", "partitions": 999, "retentionMs": -1, "config": {}},
			{"name": "team-locked.tmp", "partitions": 1, "retentionMs": 1, "config": {}},
			{"name": "other.one", "partitions": 1, "retentionMs": 1, "config": {}},
			{"name": "other.two", "partitions": 1, "retentionMs": 1, "config": {}},
		],
		"rbac": [
			{"principal": "User:u1", "resource": "Topic:x", "role": "CloudClusterAdmin"},
			{"principal": "User:u1", "resource": "Topic:x", "role": "Unknown"},
			{"principal": "User:u1", "resource": "Topic:x", "role": "ResourceOwner"},
		],
		"identityPools": [],
	},
}

worst_plan := {
	"stage": "ACTION_PLAN",
	"principal": {"id": "dev-1", "roles": []},
	"team": f.team,
	"repository": {"id": "repo-1", "ownedByTeam": true},
	"usage": f.usage({}, {}, {}),
	"ownership": {"resources": {"TOPIC:team-a.big": "repo-2"}},
	"environment": "production",
	"configuration": f.config,
	"exceptions": [],
	"manifest": {"teamId": "team-a", "repositoryId": "repo-1", "environment": "production", "topics": [], "rbac": [], "identityPools": []},
	"plan": {"planId": "plan-1"},
	"actions": [
		{"actionType": "TOPIC_CREATE", "resourceType": "TOPIC", "resourceName": "team-a.big", "payload": {"partitions": "500"}},
		{"actionType": "TOPIC_DELETE", "resourceType": "TOPIC", "resourceName": "team-a.old", "payload": {}},
		{"actionType": "RBAC_REVOKE", "resourceType": "RBAC", "resourceName": "User:u1#Topic:x#DeveloperRead", "payload": {}},
		{"actionType": "IDENTITY_POOL_DELETE", "resourceType": "IDENTITY_POOL", "resourceName": "pool-1", "payload": {}},
		{"actionType": "RBAC_ASSIGN", "resourceType": "RBAC", "resourceName": "User:u1#Topic:x#CloudClusterAdmin", "payload": {"role": "CloudClusterAdmin"}},
	],
}
