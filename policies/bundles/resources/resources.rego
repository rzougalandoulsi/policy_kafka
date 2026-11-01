package governance.resources

import data.governance.resources.limits
import data.governance.resources.rbac

# Resource-level restrictions: authorized RBAC roles and per-resource limits.

violations := rbac.violations | limits.violations

approvals := set()

contract_version := "2.0"

catalog := [
	{"policyId": "RBAC_ROLE_ALLOWED", "bundle": "resources", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "Only roles on rbac.allowedRoles may be requested."},
	{"policyId": "RBAC_ENV_RESTRICTED", "bundle": "resources", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "In environments listed under rbac.environmentRoles only those roles are permitted."},
	{"policyId": "RBAC_TEAM_ROLE_ALLOWED", "bundle": "resources", "kind": "VIOLATION", "section": "rbac", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "RBAC", "description": "A team may only grant its team.allowedRoles (team configuration) or, when that list is empty, the roles listed under rbac.teamRoles."},
	{"policyId": "MAX_TOPICS_PER_MANIFEST", "bundle": "resources", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "MANIFEST", "description": "A manifest may declare at most limits.maxTopicsPerManifest topics."},
	{"policyId": "MAX_PARTITIONS_PER_TOPIC", "bundle": "resources", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "A topic may request at most limits.maxPartitionsPerTopic partitions (per-environment override)."},
	{"policyId": "TOPIC_RETENTION_LIMIT", "bundle": "resources", "kind": "VIOLATION", "section": "limits", "severity": "DENY", "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "Retention must not exceed limits.maxRetentionMs; infinite retention (-1) is never allowed."},
]
