package governance.catalog_test

import data.governance.catalog
import data.governance.fixtures as f

test_policy_ids_are_unique if {
	count(catalog.ids) == count(catalog.policies)
}

test_hard_deny_is_never_bypassable if {
	count({p | some p in catalog.policies; p.severity == "HARD_DENY"; p.bypassable == true}) == 0
}

test_approvals_are_never_bypassable if {
	count({p | some p in catalog.policies; p.kind == "APPROVAL"; p.bypassable == true}) == 0
}

test_every_section_is_a_configuration_section if {
	every p in catalog.policies {
		p.section in {"naming", "rbac", "limits", "destructive", "approvals"}
	}
}

# Every violation and approval the rules actually emit against a worst-case input must be catalogued,
# with the same severity and bypassable flag.
test_emitted_violations_are_catalogued if {
	emitted := data.governance.naming.violations | data.governance.rbac.violations | data.governance.limits.violations with input as worst_request
	count(emitted) > 0
	every v in emitted {
		some p in catalog.policies
		p.policyId == v.policyId
		p.severity == v.severity
		p.bypassable == v.bypassable
	}
}

test_emitted_plan_violations_and_approvals_are_catalogued if {
	emitted := data.governance.limits.violations | data.governance.destructive.violations | data.governance.rbac.violations with input as worst_plan
	count(emitted) > 0
	every v in emitted {
		some p in catalog.policies
		p.policyId == v.policyId
		p.severity == v.severity
		p.bypassable == v.bypassable
	}
	approvals := data.governance.approval.approvals with input as worst_plan
	count(approvals) > 0
	every a in approvals {
		some p in catalog.policies
		p.policyId == a.policyId
		p.kind == "APPROVAL"
	}
}

worst_request := {
	"stage": "REQUEST",
	"principal": {"id": "dev-1", "roles": []},
	"team": {"id": "team-locked"},
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
	"team": {"id": "team-a"},
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
