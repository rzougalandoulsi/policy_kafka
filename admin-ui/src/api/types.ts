/**
 * Hand-maintained mirror of the Governance API records (governance/src/main/java/.../api and domain).
 * Instants are ISO-8601 strings. Run scripts/gen-api.sh to compare against the live OpenAPI document.
 */

export type VersionStatus = 'DRAFT' | 'UPLOADED' | 'ACTIVE' | 'RETIRED' | 'FAILED';

/** api/ApiError.java */
export interface ApiErrorBody {
  code: string;
  message: string;
  timestamp: string;
  errors: string[];
  details: Record<string, unknown>;
}

/** domain/exception/PolicyException.java */
export interface PolicyException {
  id: string;
  policyId: string;
  principalId: string | null;
  teamId: string;
  environment: string;
  resourcePattern: string;
  actionTypes: string[];
  reason: string;
  expiresAt: string;
  createdBy: string;
  createdAt: string;
  revokedBy: string | null;
  revokedAt: string | null;
  revokeReason: string | null;
}

/** ExceptionAdminController.CreateBody */
export interface CreateExceptionBody {
  policyId: string;
  principalId?: string | null;
  teamId: string;
  environment?: string | null;
  resourcePattern?: string | null;
  actionTypes?: string[];
  expiresAt: string;
  reason: string;
}

/** domain/policy/PolicyConfiguration.java */
export interface PolicyConfiguration {
  id: string;
  version: number;
  content: PolicyConfigurationContent;
  status: VersionStatus;
  comment: string | null;
  createdBy: string;
  createdAt: string;
  activatedAt: string | null;
  retiredAt: string | null;
}

/** Shape seeded by V2__policy_management.sql; validated by PolicyConfigurationService.validate. */
export type EnforcementMode = 'enforce' | 'shadow';

export interface PolicyConfigurationContent {
  /** Per policy id: enforce (default) or shadow (recorded as a finding, never blocking). */
  enforcement?: Record<string, EnforcementMode>;
  naming: {
    topicPattern: string;
    requireTeamPrefix: boolean;
    teamPrefixSeparator: string;
    environmentRestrictions: Record<string, { forbiddenPatterns: string[] }>;
    [k: string]: unknown;
  };
  rbac: {
    allowedRoles: string[];
    forbiddenRoles: string[];
    environmentRoles: Record<string, string[]>;
    teamRoles: Record<string, string[]>;
    sensitiveRoles: string[];
    [k: string]: unknown;
  };
  limits: {
    maxTopicsPerManifest: number;
    maxPartitionsPerTopic: number;
    maxPartitionsPerPlan: number;
    maxRetentionMs: number;
    environmentOverrides: Record<
      string,
      Partial<
        Record<
          'maxTopicsPerManifest' | 'maxPartitionsPerTopic' | 'maxPartitionsPerPlan' | 'maxRetentionMs',
          number
        >
      >
    >;
    [k: string]: unknown;
  };
  destructive: {
    topicDeleteAllowedEnvironments: string[];
    rbacRevokeAllowedEnvironments: string[];
    identityPoolDeleteAllowedEnvironments: string[];
    [k: string]: unknown;
  };
  approvals: {
    productionTopicDeletion: boolean;
    highImpactPartitionThreshold: number;
    sensitiveRoleAssignment: boolean;
    sensitiveEnvironments: string[];
    [k: string]: unknown;
  };
  [k: string]: unknown;
}

/** PolicyConfigurationAdminController.CreateBody */
export interface CreatePolicyConfigurationBody {
  content: PolicyConfigurationContent | Record<string, unknown>;
  comment?: string | null;
}

/** The six bundle roots (governance/<name>); GovernanceProperties.Opa.bundles, in display order. */
export const BUNDLE_NAMES = ['core', 'manifests', 'naming', 'quotas', 'resources', 'changes'] as const;
export type BundleName = (typeof BUNDLE_NAMES)[number];

/** domain/policy/PolicyBundleVersion.java: one version of one bundle; exactly one ACTIVE per bundle name. */
export interface PolicyBundleVersion {
  id: string;
  bundleName: string;
  revision: string;
  sha256: string;
  manifest: Record<string, unknown>;
  testReport: { passed?: number; names?: string[] } | null;
  status: VersionStatus;
  comment: string | null;
  uploadedBy: string;
  uploadedAt: string;
  activatedAt: string | null;
  retiredAt: string | null;
  failureReason: string | null;
}

/** PolicyBundleAdminController.UploadBody */
export interface UploadBundleBody {
  bundleBase64: string;
  testReportJson: string;
  comment?: string | null;
}

/** PolicyBundleService.BundleDiagnostics: expected (ACTIVE) versus the revision OPA reports for one bundle. */
export interface BundleDiagnostics {
  expected: string | null;
  loaded: string | null;
  match: boolean;
}

/** PolicyBundleService.RevisionDiagnostics: the policy release versus OPA, per bundle. */
export interface RevisionDiagnostics {
  releaseId: string | null;
  /** true only when every required bundle runs exactly its ACTIVE revision */
  match: boolean;
  /** data.governance.core.ready: every bundle loaded with a compatible contract version */
  ready: boolean;
  bundles: Record<string, BundleDiagnostics>;
}

/** PolicyReleaseController.ActivateReleaseBody */
export interface ActivateReleaseBody {
  versionIds: string[];
}

/** domain/policy/PolicyRelease.java */
export interface PolicyReleaseActivation {
  id: string;
  revisions: Record<string, string>;
}

/** domain/policy/ApprovalConfiguration.java */
export interface ApprovalConfiguration {
  id: string;
  version: number;
  approverRoles: string[];
  requiredApprovals: number;
  separationOfDuties: boolean;
  ttlHours: number;
  status: VersionStatus;
  createdBy: string;
  createdAt: string;
  activatedAt: string | null;
}

/** ApprovalConfigurationAdminController.CreateBody */
export interface CreateApprovalConfigurationBody {
  approverRoles: string[];
  requiredApprovals: number;
  separationOfDuties: boolean;
  ttlHours: number;
}

/** AuditRepository.AuditEntry */
export interface AuditEntry {
  id: number;
  occurredAt: string;
  actorId: string;
  action: string;
  targetType: string;
  targetId: string;
  requestId: string | null;
  details: Record<string, unknown>;
}

export interface AuditQuery {
  targetType?: string;
  targetId?: string;
  requestId?: string;
  actor?: string;
  since?: string;
  limit?: number;
}

/** DeadLetterRepository.DeadLetter */
export interface DeadLetter {
  id: number;
  topic: string;
  partition: number;
  offset: number;
  key: string | null;
  eventId: string | null;
  requestId: string | null;
  reason: string;
  error: string | null;
  recordedAt: string;
  resolvedAt: string | null;
}

/** data.governance.core.policy_catalog via GET /api/v1/admin/policies */
export interface CatalogPolicy {
  policyId: string;
  /** bundle that declares the policy (core, naming, ...) */
  bundle?: string;
  kind: 'VIOLATION' | 'APPROVAL';
  section: 'naming' | 'rbac' | 'limits' | 'destructive' | 'approvals' | 'manifests';
  severity: 'HARD_DENY' | 'DENY' | 'REQUIRE_APPROVAL';
  bypassable: boolean;
  stages: Array<'REQUEST' | 'ACTION_PLAN'>;
  resourceType: string;
  description: string;
}

/** Action types the Orchestrator emits (contracts + simulator); exceptions may be scoped to them. */
export const KNOWN_ACTION_TYPES = [
  'TOPIC_CREATE',
  'TOPIC_UPDATE',
  'TOPIC_DELETE',
  'RBAC_ASSIGN',
  'RBAC_REVOKE',
  'IDENTITY_POOL_CREATE',
  'IDENTITY_POOL_DELETE',
] as const;

export const AUDIT_TARGET_TYPES = [
  'policy_exception',
  'policy_configuration',
  'policy_bundle',
  'policy_release',
  'approval_configuration',
  'approval_request',
  'manifest_request',
  'team_configuration',
  'kafka_dead_letter',
] as const;

// ---------------------------------------------------------------- team configuration, usage, shadow findings (input contract 2.0)

/** TeamConfiguration.QUOTA_KEYS: the usage counters and quota keys of input contract 2.0, in display order. */
export const QUOTA_KEYS = [
  'topics',
  'topicPartitions',
  'identityPools',
  'rbacBindings',
  'schemas',
  'serviceAccounts',
  'connectors',
  'flinkStatements',
] as const;
export type QuotaKey = (typeof QUOTA_KEYS)[number];

/** Quota keys the current contracts actually count; the others stay 0 until the resource types exist (plan phase 6). */
export const COUNTED_QUOTA_KEYS: readonly QuotaKey[] = ['topics', 'topicPartitions', 'identityPools', 'rbacBindings'];

export type TeamSource = 'governance' | 'external' | 'default';

/** domain/team/TeamConfiguration.java; source=default (version 0) is what a team without a stored row evaluates against. */
export interface TeamConfiguration {
  teamId: string;
  version: number;
  authorizedPrefixes: string[];
  quotas: Partial<Record<QuotaKey, number>>;
  allowedRoles: string[];
  environments: Record<string, Record<string, unknown>>;
  source: TeamSource;
  sourceVersion: string | null;
  syncedAt: string | null;
  comment: string | null;
  updatedBy: string;
  createdAt: string | null;
  updatedAt: string | null;
}

/** TeamConfigurationAdminController.UpsertBody */
export interface UpsertTeamBody {
  authorizedPrefixes: string[];
  quotas: Partial<Record<QuotaKey, number>>;
  allowedRoles: string[];
  environments: Record<string, Record<string, unknown>>;
  source: 'governance' | 'external';
  sourceVersion?: string | null;
  comment?: string | null;
}

/** domain/team/TeamConfigurationChange.java */
export interface TeamConfigurationChange {
  id: number;
  teamId: string;
  version: number;
  content: Record<string, unknown>;
  action: 'CREATED' | 'UPDATED' | 'DELETED';
  changedBy: string;
  changedAt: string;
  comment: string | null;
}

/** TeamConfigurationService.TeamUsage */
export interface TeamUsage {
  teamId: string;
  currentByEnvironment: Record<string, Partial<Record<QuotaKey, number>>>;
  reservedByEnvironment: Record<string, Partial<Record<QuotaKey, number>>>;
  repositories: Array<{
    repositoryId: string;
    environment: string;
    /** -1 until a confirmed snapshot was consumed from teams.repo.manifest */
    stateVersion: number;
    counts: Partial<Record<QuotaKey, number>>;
    topics: string[];
    identityPools: string[];
    rbacBindings: string[];
    snapshotAt: string | null;
  }>;
  reservations: Array<{
    id: string;
    environment: string;
    requestId: string;
    planId: string;
    deltas: Partial<Record<QuotaKey, number>>;
    createdAt: string;
    expiresAt: string;
  }>;
}

/** ShadowFindingRepository.ShadowFinding */
export interface ShadowFinding {
  id: number;
  decisionId: string;
  requestId: string;
  planId: string | null;
  stage: 'REQUEST' | 'ACTION_PLAN';
  kind: 'VIOLATION' | 'APPROVAL';
  policyId: string;
  severity: string;
  message: string | null;
  resourceType: string | null;
  resourceName: string | null;
  recordedAt: string;
  teamId: string;
  repositoryId: string;
  environment: string;
  principalId: string;
}

export interface ShadowFindingQuery {
  policyId?: string;
  teamId?: string;
  environment?: string;
  since?: string;
  limit?: number;
}
