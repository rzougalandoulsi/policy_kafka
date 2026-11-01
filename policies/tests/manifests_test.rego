package governance.manifests_test

import data.governance.fixtures as f
import data.governance.manifests

ids(vs) := {v.policyId | some v in vs}

test_no_conflicts if {
	count(manifests.violations) == 0 with input as f.request("development", [f.topic("team-a.orders", 6)], [])
}

test_topic_owned_by_other_repository if {
	inp := f.with_section(f.request("development", [f.topic("team-a.orders", 6)], []), "ownership", {"resources": {"TOPIC:team-a.orders": "repo-2"}})
	vs := manifests.violations with input as inp
	ids(vs) == {"RESOURCE_OWNED_BY_OTHER_REPOSITORY"}
	some v in vs
	v.resourceType == "TOPIC"
	v.resourceName == "team-a.orders"
	v.bypassable == true
	contains(v.message, "repo-2")
}

test_ownership_entries_for_undeclared_resources_are_ignored if {
	inp := f.with_section(f.request("development", [f.topic("team-a.orders", 6)], []), "ownership", {"resources": {"TOPIC:team-a.other": "repo-2"}})
	count(manifests.violations) == 0 with input as inp
}

test_identity_pool_owned_by_other_repository if {
	base := f.request("development", [], [])
	inp := f.with_section(object.union(base, {"manifest": object.union(base.manifest, {"identityPools": [{"name": "pool-1", "principals": [], "rbac": []}]})}), "ownership", {"resources": {"IDENTITY_POOL:pool-1": "team-b/repo-9"}})
	vs := manifests.violations with input as inp
	some v in vs
	v.policyId == "RESOURCE_OWNED_BY_OTHER_REPOSITORY"
	v.resourceType == "IDENTITY_POOL"
}

test_create_action_for_owned_topic_at_plan_stage if {
	inp := f.with_section(f.plan("development", [f.action("TOPIC_CREATE", "TOPIC", "team-a.orders", {})]), "ownership", {"resources": {"TOPIC:team-a.orders": "repo-2"}})
	"RESOURCE_OWNED_BY_OTHER_REPOSITORY" in ids(manifests.violations) with input as inp
	del := f.with_section(f.plan("development", [f.action("TOPIC_DELETE", "TOPIC", "team-a.orders", {})]), "ownership", {"resources": {"TOPIC:team-a.orders": "repo-2"}})
	count(manifests.violations) == 0 with input as del
}

test_repository_owned_by_other_team if {
	inp := f.with_section(f.request("development", [], []), "repository", {"id": "repo-1", "ownedByTeam": false})
	vs := manifests.violations with input as inp
	ids(vs) == {"REPOSITORY_OWNED_BY_OTHER_TEAM"}
	some v in vs
	v.resourceName == "repo-1"
}
