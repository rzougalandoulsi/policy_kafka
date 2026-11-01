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
  ShadowFinding,
  TeamConfiguration,
  TeamConfigurationChange,
  TeamUsage,
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
    policyId: 'AUTHORIZED_PREFIX_REQUIRED',
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

function version(
  n: number,
  bundleName: string,
  revision: string,
  status: PolicyBundleVersion['status'],
  extra: Partial<PolicyBundleVersion> = {},
): PolicyBundleVersion {
  return {
    id: `bundle-${n}`,
    bundleName,
    revision,
    sha256: String.fromCharCode(96 + n).repeat(64),
    manifest: { revision, roots: [`governance/${bundleName}`], rego_version: 1 },
    testReport: { passed: 53, names: [] },
    status,
    comment: null,
    uploadedBy: 'admin-1',
    uploadedAt: '2026-10-20T00:00:00Z',
    activatedAt: status === 'UPLOADED' ? null : '2026-10-20T00:00:00Z',
    retiredAt: null,
    failureReason: null,
    ...extra,
  };
}

/** Core has a history (retired, active, failed); naming has a newer upload waiting; the rest are simply active. */
export const bundles: PolicyBundleVersion[] = [
  version(1, 'core', 'core-v1', 'RETIRED', {
    comment: 'first',
    testReport: { passed: 40, names: [] },
    uploadedAt: '2026-10-01T00:00:00Z',
    activatedAt: '2026-10-01T00:00:00Z',
    retiredAt: '2026-10-20T00:00:00Z',
  }),
  version(2, 'core', 'core-v2', 'ACTIVE', { comment: 'catalogue added' }),
  version(3, 'core', 'core-v3', 'FAILED', {
    comment: 'broken',
    uploadedAt: '2026-10-25T00:00:00Z',
    activatedAt: '2026-10-25T00:00:00Z',
    failureReason: 'OPA did not load revision; OPA reports core-v2',
  }),
  version(4, 'manifests', 'manifests-v2', 'ACTIVE'),
  version(5, 'naming', 'naming-v2', 'ACTIVE'),
  version(6, 'quotas', 'quotas-v2', 'ACTIVE'),
  version(7, 'resources', 'resources-v2', 'ACTIVE'),
  version(8, 'changes', 'changes-v2', 'ACTIVE'),
  version(9, 'naming', 'naming-v3', 'UPLOADED', {
    comment: 'multi-prefix',
    uploadedAt: '2026-10-26T00:00:00Z',
  }),
];

export const RELEASE_ID = 'rel-2f9c0a1b3d4e';

export const diagnostics: RevisionDiagnostics = {
  releaseId: RELEASE_ID,
  match: true,
  ready: true,
  bundles: Object.fromEntries(
    ['core', 'manifests', 'naming', 'quotas', 'resources', 'changes'].map((n) => [
      n,
      { expected: `${n}-v2`, loaded: `${n}-v2`, match: true },
    ]),
  ),
};

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
    bundle: 'naming',
    kind: 'VIOLATION',
    section: 'naming',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST'],
    resourceType: 'TOPIC',
    description: 'Topic names must match naming.topicPattern.',
  },
  {
    policyId: 'AUTHORIZED_PREFIX_REQUIRED',
    bundle: 'naming',
    kind: 'VIOLATION',
    section: 'naming',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST'],
    resourceType: 'TOPIC',
    description: "Topics must start with one of the team's authorised prefixes.",
  },
  {
    policyId: 'TEAM_QUOTA_EXCEEDED',
    bundle: 'quotas',
    kind: 'VIOLATION',
    section: 'limits',
    severity: 'DENY',
    bypassable: true,
    stages: ['REQUEST', 'ACTION_PLAN'],
    resourceType: 'QUOTA',
    description: 'Projected team usage must stay within the team quotas.',
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

export const teams: TeamConfiguration[] = [
  {
    teamId: 'team-a',
    version: 3,
    authorizedPrefixes: ['team-a.', 'orders.'],
    quotas: { topics: 25, topicPartitions: 200 },
    allowedRoles: [],
    environments: { production: { changeWindow: 'weekdays' } },
    source: 'governance',
    sourceVersion: null,
    syncedAt: null,
    comment: 'orders domain moved in',
    updatedBy: 'admin-1',
    createdAt: '2026-10-10T00:00:00Z',
    updatedAt: '2026-10-28T09:00:00Z',
  },
  {
    teamId: 'team-ext',
    version: 1,
    authorizedPrefixes: ['ext.'],
    quotas: { topics: 5 },
    allowedRoles: ['DeveloperRead'],
    environments: {},
    source: 'external',
    sourceVersion: 'registry-42',
    syncedAt: '2026-10-30T00:00:00Z',
    comment: null,
    updatedBy: 'sync',
    createdAt: '2026-10-30T00:00:00Z',
    updatedAt: '2026-10-30T00:00:00Z',
  },
];

export const teamDefaults = (teamId: string): TeamConfiguration => ({
  teamId,
  version: 0,
  authorizedPrefixes: [`${teamId}.`],
  quotas: {},
  allowedRoles: [],
  environments: {},
  source: 'default',
  sourceVersion: null,
  syncedAt: null,
  comment: null,
  updatedBy: 'system',
  createdAt: null,
  updatedAt: null,
});

export const teamHistory: TeamConfigurationChange[] = [
  {
    id: 3,
    teamId: 'team-a',
    version: 3,
    content: { authorizedPrefixes: ['team-a.', 'orders.'], quotas: { topics: 25, topicPartitions: 200 } },
    action: 'UPDATED',
    changedBy: 'admin-1',
    changedAt: '2026-10-28T09:00:00Z',
    comment: 'orders domain moved in',
  },
  {
    id: 1,
    teamId: 'team-a',
    version: 1,
    content: { authorizedPrefixes: ['team-a.'], quotas: {} },
    action: 'CREATED',
    changedBy: 'admin-1',
    changedAt: '2026-10-10T00:00:00Z',
    comment: null,
  },
];

export const teamUsage: TeamUsage = {
  teamId: 'team-a',
  currentByEnvironment: {
    development: { topics: 4, topicPartitions: 10, identityPools: 0, rbacBindings: 1 },
    production: { topics: 1, topicPartitions: 6, identityPools: 0, rbacBindings: 0 },
  },
  reservedByEnvironment: { production: { topics: 2, topicPartitions: 128 } },
  repositories: [
    {
      repositoryId: 'repo-1',
      environment: 'development',
      stateVersion: 7,
      counts: { topics: 3, topicPartitions: 9, identityPools: 0, rbacBindings: 1 },
      topics: ['team-a.orders', 'team-a.payments', 'team-a.audit'],
      identityPools: [],
      rbacBindings: ['User:sa-1#Topic:team-a.orders#DeveloperRead'],
      snapshotAt: '2026-11-01T11:59:00Z',
    },
    {
      repositoryId: 'repo-2',
      environment: 'development',
      stateVersion: 1,
      counts: { topics: 1, topicPartitions: 1, identityPools: 0, rbacBindings: 0 },
      topics: ['team-a.repo2.events'],
      identityPools: [],
      rbacBindings: [],
      snapshotAt: '2026-11-01T11:50:00Z',
    },
    {
      repositoryId: 'repo-1',
      environment: 'production',
      stateVersion: 2,
      counts: { topics: 1, topicPartitions: 6, identityPools: 0, rbacBindings: 0 },
      topics: ['team-a.orders'],
      identityPools: [],
      rbacBindings: [],
      snapshotAt: '2026-11-01T10:00:00Z',
    },
  ],
  reservations: [
    {
      id: 'qr-1',
      environment: 'production',
      requestId: 'req-11111111-aaaa',
      planId: 'plan-1',
      deltas: { topics: 2, topicPartitions: 128 },
      createdAt: '2026-11-01T11:58:00Z',
      expiresAt: '2026-11-01T12:13:00Z',
    },
  ],
};

export const shadowFindings: ShadowFinding[] = [
  {
    id: 2,
    decisionId: 'dec-2',
    requestId: 'req-22222222-bbbb',
    planId: null,
    stage: 'REQUEST',
    kind: 'VIOLATION',
    policyId: 'AUTHORIZED_PREFIX_REQUIRED',
    severity: 'DENY',
    message: "topic 'other.thing' must start with an authorised prefix of team 'team-a' (team-a.)",
    resourceType: 'TOPIC',
    resourceName: 'other.thing',
    recordedAt: '2026-11-01T11:30:00Z',
    teamId: 'team-a',
    repositoryId: 'repo-3',
    environment: 'development',
    principalId: 'dev-1',
  },
  {
    id: 1,
    decisionId: 'dec-1',
    requestId: 'req-33333333-cccc',
    planId: 'plan-9',
    stage: 'ACTION_PLAN',
    kind: 'APPROVAL',
    policyId: 'PROD_TOPIC_DELETE_REQUIRES_APPROVAL',
    severity: 'REQUIRE_APPROVAL',
    message: "deleting topic 'team-a.old' in production requires approval",
    resourceType: 'TOPIC',
    resourceName: 'team-a.old',
    recordedAt: '2026-11-01T10:30:00Z',
    teamId: 'team-a',
    repositoryId: 'repo-1',
    environment: 'production',
    principalId: 'dev-1',
  },
];
