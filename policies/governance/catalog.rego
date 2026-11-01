package governance.catalog

# Registry of every policy this bundle can emit, served to operators through
# GET /api/v1/admin/policies (Governance proxies data.governance.catalog.policies).
# Keep in sync with the rules: catalog_test.rego fails when a rule emits an unknown id.
#
# kind      VIOLATION (can DENY) | APPROVAL (asks for human approval, never denies)
# severity  HARD_DENY | DENY | REQUIRE_APPROVAL
# bypassable true when a scoped exception may exempt the violation (never for HARD_DENY)

policies := [
	{"policyId": "TOPIC_NAME_PATTERN", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topic names must match configuration naming.topicPattern."},
	{"policyId": "TEAM_PREFIX_REQUIRED", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topics must start with the owning team prefix when naming.requireTeamPrefix is true."},
	{"policyId": "ENV_NAME_RESTRICTION", "kind": "VIOLATION", "section": "naming", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Topic names must not match naming.environmentRestrictions[env].forbiddenPatterns."},
	{"policyId": "RBAC_ADMIN_ROLE_FORBIDDEN", "kind": "VIOLATION", "section": "rbac", "severity": "HARD_DENY", "bypassable": false, "stages": ["REQUEST", "ACTION_PLAN"], "resourceType": "RBAC", "description": "Administrative roles (rbac.forbiddenRoles) can never be granted; no exception and no approval applies."},
	{"policyId": "RBAC_ROLE_ALLOWED", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "Only roles on rbac.allowedRoles may be requested."},
	{"policyId": "RBAC_ENV_RESTRICTED", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "In environments listed under rbac.environmentRoles only those roles are permitted."},
	{"policyId": "RBAC_TEAM_ROLE_ALLOWED", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "Teams listed under rbac.teamRoles may only grant those roles."},
	{"policyId": "MAX_TOPICS_PER_MANIFEST", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "MANIFEST", "description": "A manifest may declare at most limits.maxTopicsPerManifest topics."},
	{"policyId": "MAX_PARTITIONS_PER_TOPIC", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "A topic may request at most limits.maxPartitionsPerTopic partitions (per-environment override)."},
	{"policyId": "MAX_PARTITIONS_PER_PLAN", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "PLAN", "description": "One plan may create at most limits.maxPartitionsPerPlan partitions in total."},
	{"policyId": "TOPIC_RETENTION_LIMIT", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Retention must not exceed limits.maxRetentionMs; infinite retention (-1) is never allowed."},
	{"policyId": "TOPIC_DELETE_RESTRICTED", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "TOPIC", "description": "Topic deletion is only allowed in destructive.topicDeleteAllowedEnvironments."},
	{"policyId": "RBAC_REVOKE_RESTRICTED", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "RBAC", "description": "Revoking bindings is only allowed in destructive.rbacRevokeAllowedEnvironments."},
	{"policyId": "IDENTITY_POOL_DELETE_RESTRICTED", "kind": "VIOLATION", "section": "destructive", "severity": "DENY", "bypassable": true, "stages": ["ACTION_PLAN"], "resourceType": "IDENTITY_POOL", "description": "Identity pool deletion is only allowed in destructive.identityPoolDeleteAllowedEnvironments."},
	{"policyId": "PROD_TOPIC_DELETE_REQUIRES_APPROVAL", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["ACTION_PLAN"], "resourceType": "TOPIC", "description": "Deleting a topic in a sensitive environment requires approval when approvals.productionTopicDeletion is true."},
	{"policyId": "HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["ACTION_PLAN"], "resourceType": "PLAN", "description": "Plans creating at least approvals.highImpactPartitionThreshold partitions require approval."},
	{"policyId": "SENSITIVE_PERMISSION_REQUIRES_APPROVAL", "kind": "APPROVAL", "section": "approvals", "severity": "REQUIRE_APPROVAL", "bypassable": false, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "Granting rbac.sensitiveRoles in a sensitive environment requires approval when approvals.sensitiveRoleAssignment is true."},
]

ids := {p.policyId | some p in policies}

bypassable_ids := {p.policyId | some p in policies; p.bypassable == true}
