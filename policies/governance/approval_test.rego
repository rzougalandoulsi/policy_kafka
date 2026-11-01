package governance.approval_test

import data.governance.approval
import data.governance.fixtures as f

ids(xs) := {a.policyId | some a in xs}

test_prod_topic_delete_requires_approval if {
	"PROD_TOPIC_DELETE_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.plan("production", [f.action("TOPIC_DELETE", "TOPIC", "team-a.a", {})])
	not "PROD_TOPIC_DELETE_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.plan("development", [f.action("TOPIC_DELETE", "TOPIC", "team-a.a", {})])
}

test_high_impact_plan_requires_approval if {
	"HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.plan("development", [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "50"})])
	not "HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.plan("development", [f.action("TOPIC_CREATE", "TOPIC", "team-a.a", {"partitions": "49"})])
}

test_sensitive_role_in_production_requires_approval if {
	b := {"principal": "sa", "resource": "r", "role": "DeveloperManage"}
	"SENSITIVE_PERMISSION_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.request("production", [], [b])
	not "SENSITIVE_PERMISSION_REQUIRES_APPROVAL" in ids(approval.approvals) with input as f.request("development", [], [b])
}

test_switch_disables_prod_delete_approval if {
	cfg := object.union(f.config, {"approvals": object.union(f.config.approvals, {"productionTopicDeletion": false})})
	inp := object.union(f.plan("production", [f.action("TOPIC_DELETE", "TOPIC", "team-a.a", {})]), {"configuration": cfg})
	not "PROD_TOPIC_DELETE_REQUIRES_APPROVAL" in ids(approval.approvals) with input as inp
}
