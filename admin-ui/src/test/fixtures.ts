import type {
  ApprovalConfiguration,
  AuditEntry,
  CatalogPolicy,
  DeadLetter,
  PolicyBundleVersion,
  PolicyConfiguration,
  PolicyConfigurationContent,
  PolicyException,
  RevisionDiagnostics,
} from '@/api/types';

export const NOW = new Date('2026-11-01T12:00:00Z');

export const seedConfigContent: PolicyConfigurationContent = {
  naming: {
    topicPattern: '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$',
    requireTeamPrefix: true,
    teamPrefixSeparator: '.',
    environmentRestrictions: {
      production: { forbiddenPatterns: ['^[a-z0-9-]+\\.(tmp|test|scratch)(\\.|$)'] },
    },
  },
  rbac: {
    allowedRoles: ['DeveloperRead', 'DeveloperWrite', 'DeveloperManage', 'ResourceOwner'],
    forbiddenRoles: ['CloudClusterAdmin', 'EnvironmentAdmin', 'OrganizationAdmin', 'SystemAdmin'],
    environmentRoles: { production: ['DeveloperRead', 'DeveloperWrite', 'DeveloperManage'] },
    teamRoles: {},
    sensitiveRoles: ['DeveloperManage', 'ResourceOwner'],
  },
  limits: {
    maxTopicsPerManifest: 50,
    maxPartitionsPerTopic: 64,
    maxPartitionsPerPlan: 512,
    maxRetentionMs: 2592000000,
    environmentOverrides: { development: { maxPartitionsPerTopic: 12, maxRetentionMs: 604800000 } },
  },
  destructive: {
    topicDeleteAllowedEnvironments: ['development', 'staging', 'production'],
    rbacRevokeAllowedEnvironments: ['development', 'staging', 'production'],
    identityPoolDeleteAllowedEnvironments: ['development', 'staging'],
  },
  approvals: {
    productionTopicDeletion: true,
    highImpactPartitionThreshold: 128,
    sensitiveRoleAssignment: true,
    sensitiveEnvironments: ['production'],
  },
};

export const policyConfigs: PolicyConfiguration[] = [
  {
    id: 'cfg-initial',
    version: 1,
    content: seedConfigContent,
    status: 'RETIRED',
    comment: 'Initial configuration',
    createdBy: 'system',
    createdAt: '2026-10-01T00:00:00Z',
    activatedAt: '2026-10-01T00:00:00Z',
    retiredAt: '2026-10-20T00:00:00Z',
  },
  {
    id: 'cfg-2',
    version: 2,
    content: { ...seedConfigContent, limits: { ...seedConfigContent.limits, maxPartitionsPerTopic: 96 } },
    status: 'ACTIVE',
    comment: 'Raise partitions per topic',
    createdBy: 'admin-1',
    createdAt: '2026-10-20T00:00:00Z',
    activatedAt: '2026-10-20T00:00:00Z',
    retiredAt: null,
  },
  {
    id: 'cfg-3',
    version: 3,
    content: { ...seedConfigContent, limits: { ...seedConfigContent.limits, maxTopicsPerManifest: 80 } },
    status: 'DRAFT',
    comment: 'Draft: more topics',
    createdBy: 'admin-1',
    createdAt: '2026-10-30T00:00:00Z',
    activatedAt: null,
    retiredAt: null,
  },
];

export const exceptions: PolicyException[] = [
  {
    id: 'exc-11111111-1111-1111-1111-111111111111',
    policyId: 'MAX_PARTITIONS_PER_TOPIC',
    principalId: null,
    teamId: 'team-a',
    environment: 'development',
    resourcePattern: 'team-a.*',
    actionTypes: [],
    reason: 'Load test needs 24 partitions',
    expiresAt: '2026-11-20T00:00:00Z',
    createdBy: 'admin-1',
    createdAt: '2026-10-25T10:00:00Z',
    revokedBy: null,
    revokedAt: null,
    revokeReason: null,
  },
  {
    id: 'exc-22222222-2222-2222-2222-222222222222',
    policyId: 'TOPIC_DELETE_RESTRICTED',
    principalId: 'dev-1',
    teamId: 'team-a',
    environment: 'production',
    resourcePattern: 'team-a.legacy.*',
    actionTypes: ['TOPIC_DELETE'],
    reason: 'Decommission legacy topics',
    expiresAt: '2026-11-03T00:00:00Z',
    createdBy: 'admin-1',
    createdAt: '2026-10-28T10:00:00Z',
    revokedBy: null,
    revokedAt: null,
    revokeReason: null,
  },
  {
    id: 'exc-33333333-3333-3333-3333-333333333333',
    policyId: 'TEAM_PREFIX_REQUIRED',
    principalId: null,
    teamId: 'team-b',
    environment: '*',
    resourcePattern: 'shared.*',
    actionTypes: [],
    reason: 'Shared topics owned jointly',
    expiresAt: '2027-01-01T00:00:00Z',
    createdBy: 'admin-1',
    createdAt: '2026-10-01T10:00:00Z',
    revokedBy: 'admin-1',
    revokedAt: '2026-10-15T10:00:00Z',
    revokeReason: 'No longer needed',
  },
];

export const bundles: PolicyBundleVersion[] = [
  {
    id: 'bundle-1',
    revision: 'v1',
    sha256: 'a'.repeat(64),
    manifest: { revision: 'v1', roots: ['governance'] },
    testReport: { passed: 40, names: [] },
    status: 'RETIRED',
    comment: 'first',
    uploadedBy: 'admin-1',
    uploadedAt: '2026-10-01T00:00:00Z',
    activatedAt: '2026-10-01T00:00:00Z',
    retiredAt: '2026-10-20T00:00:00Z',
    failureReason: null,
  },
  {
    id: 'bundle-2',
    revision: 'v2',
    sha256: 'b'.repeat(64),
    manifest: { revision: 'v2', roots: ['governance'] },
    testReport: { passed: 46, names: [] },
    status: 'ACTIVE',
    comment: 'catalogue added',
    uploadedBy: 'admin-1',
    uploadedAt: '2026-10-20T00:00:00Z',
    activatedAt: '2026-10-20T00:00:00Z',
    retiredAt: null,
    failureReason: null,
  },
  {
    id: 'bundle-3',
    revision: 'v3',
    sha256: 'c'.repeat(64),
    manifest: { revision: 'v3', roots: ['governance'] },
    testReport: { passed: 47, names: [] },
    status: 'FAILED',
    comment: 'broken',
    uploadedBy: 'admin-1',
    uploadedAt: '2026-10-25T00:00:00Z',
    activatedAt: '2026-10-25T00:00:00Z',
    retiredAt: null,
    failureReason: 'OPA did not load revision; OPA reports v2',
  },
];

export const diagnostics: RevisionDiagnostics = { activeRevision: 'v2', opaRevision: 'v2', match: true };

export const approvalConfigs: ApprovalConfiguration[] = [
  {
    id: 'apr-initial',
    version: 1,
    approverRoles: ['governance-approver'],
    requiredApprovals: 1,
    separationOfDuties: true,
    ttlHours: 72,
    status: 'ACTIVE',
    createdBy: 'system',
    createdAt: '2026-10-01T00:00:00Z',
    activatedAt: '2026-10-01T00:00:00Z',
  },
];

export const auditEntries: AuditEntry[] = [
  {
    id: 3,
    occurredAt: '2026-10-28T10:00:00Z',
    actorId: 'admin-1',
    action: 'EXCEPTION_CREATED',
    targetType: 'policy_exception',
    targetId: exceptions[1].id,
    requestId: null,
    details: { policyId: 'TOPIC_DELETE_RESTRICTED', teamId: 'team-a' },
  },
  {
    id: 2,
    occurredAt: '2026-10-25T10:00:00Z',
    actorId: 'admin-1',
    action: 'EXCEPTION_CREATED',
    targetType: 'policy_exception',
    targetId: exceptions[0].id,
    requestId: null,
    details: { policyId: 'MAX_PARTITIONS_PER_TOPIC', teamId: 'team-a' },
  },
  {
    id: 1,
    occurredAt: '2026-10-20T00:00:00Z',
    actorId: 'admin-1',
    action: 'POLICY_CONFIGURATION_ACTIVATED',
    targetType: 'policy_configuration',
    targetId: 'cfg-2',
    requestId: null,
    details: { version: 2 },
  },
];

export const deadLetters: DeadLetter[] = [];

export const catalog: CatalogPolicy[] = [
  {
    policyId: 'TOPIC_NAME_PATTERN',
    kind: 'VIOLATION',
    section: 'naming',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST'],
    resourceType: 'TOPIC',
    description: 'Topic names must match naming.topicPattern.',
  },
  {
    policyId: 'TEAM_PREFIX_REQUIRED',
    kind: 'VIOLATION',
    section: 'naming',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST'],
    resourceType: 'TOPIC',
    description: 'Topics must start with the team prefix.',
  },
  {
    policyId: 'RBAC_ADMIN_ROLE_FORBIDDEN',
    kind: 'VIOLATION',
    section: 'rbac',
    severity: 'HARD_DENY',
    bypassable: false,
    stages: ['REQUEST', 'ACTION_PLAN'],
    resourceType: 'RBAC',
    description: 'Administrative roles can never be granted.',
  },
  {
    policyId: 'MAX_PARTITIONS_PER_TOPIC',
    kind: 'VIOLATION',
    section: 'limits',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST'],
    resourceType: 'TOPIC',
    description: 'Partition cap per topic.',
  },
  {
    policyId: 'TOPIC_DELETE_RESTRICTED',
    kind: 'VIOLATION',
    section: 'destructive',
    severity: 'DENY',
    bypassable: true,
    stages: ['ACTION_PLAN'],
    resourceType: 'TOPIC',
    description: 'Topic deletion per environment.',
  },
  {
    policyId: 'PROD_TOPIC_DELETE_REQUIRES_APPROVAL',
    kind: 'APPROVAL',
    section: 'approvals',
    severity: 'REQUIRE_APPROVAL',
    bypassable: false,
    stages: ['ACTION_PLAN'],
    resourceType: 'TOPIC',
    description: 'Prod deletions need approval.',
  },
];

/** Unsigned JWT good enough for the UI (the API is mocked). */
export function fakeJwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ exp: Math.floor(Date.now() / 1000) + 3600, iss: 'https://test', ...claims })}.sig`;
}

export const ADMIN_TOKEN = fakeJwt({ sub: 'admin-1', roles: ['governance-admin', 'governance-auditor'] });
export const AUDITOR_TOKEN = fakeJwt({ sub: 'auditor-1', roles: ['governance-auditor'] });
export const DEV_TOKEN = fakeJwt({ sub: 'dev-1', roles: [], teams: ['team-a'] });
