package governance.changes

import data.governance.changes.approval
import data.governance.changes.destructive

# Change governance: destructive operations, production restrictions, approval requirements.

violations := destructive.violations

approvals := approval.approvals

contract_version := "2.0"

catalog := [
	{"policyId": "TOPIC_DELETE_RESTRICTED", "bundle": "changes", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "TOPIC", "description": "Topic deletion is only allowed in destructive.topicDeleteAllowedEnvironments."},
	{"policyId": "RBAC_REVOKE_RESTRICTED", "bundle": "changes", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "RBAC", "description": "Revoking bindings is only allowed in destructive.rbacRevokeAllowedEnvironments."},
	{"policyId": "IDENTITY_POOL_DELETE_RESTRICTED", "bundle": "changes", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "IDENTITY_POOL", "description": "Identity pool deletion is only allowed in destructive.identityPoolDeleteAllowedEnvironments."},
	{"policyId": "PROD_TOPIC_DELETE_REQUIRES_APPROVAL", "bundle": "changes", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["ACTION_PLAN"], "resourceType": "TOPIC", "description": "Deleting a topic in a sensitive environment requires approval when approvals.productionTopicDeletion is true."},
	{"policyId": "HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL", "bundle": "changes", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["ACTION_PLAN"], "resourceType": "PLAN", "description": "Plans creating at least approvals.highImpactPartitionThreshold partitions require approval."},
	{"policyId": "SENSITIVE_PERMISSION_REQUIRES_APPROVAL", "bundle": "changes", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "Granting rbac.sensitiveRoles in a sensitive environment requires approval when approvals.sensitiveRoleAssignment is true."},
]
