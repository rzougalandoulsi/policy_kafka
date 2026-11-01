import type { PolicyConfigurationContent } from '@/api/types';

/** Mirrors PolicyConfigurationService.validate plus client-side hints. */
export const REQUIRED_SECTIONS = ['naming', 'rbac', 'limits', 'destructive', 'approvals'] as const;
export const LIMIT_KEYS = [
  'maxTopicsPerManifest',
  'maxPartitionsPerTopic',
  'maxPartitionsPerPlan',
  'maxRetentionMs',
] as const;
export type LimitKey = (typeof LIMIT_KEYS)[number];

export interface ValidationResult {
  /** Blocking problems, identical in spirit to the server's IllegalArgumentException messages. */
  errors: string[];
  /** Non-blocking hints. */
  warnings: string[];
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function validateContent(content: unknown): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!isObject(content)) return { errors: ['Policy configuration must be a JSON object'], warnings };
  const missing = REQUIRED_SECTIONS.filter((s) => !isObject(content[s]));
  if (missing.length) errors.push(`Policy configuration is missing object sections: [${missing.join(', ')}]`);
  const limits = content.limits;
  if (isObject(limits)) {
    for (const k of LIMIT_KEYS) {
      const v = limits[k];
      if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0)
        errors.push(`limits.${k} must be a positive number`);
    }
  }
  const enforcement = content.enforcement;
  if (enforcement !== undefined) {
    if (!isObject(enforcement)) errors.push('enforcement must be an object of policyId -> enforce|shadow');
    else
      for (const [id, mode] of Object.entries(enforcement)) {
        if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(id)) errors.push(`enforcement key '${id}' is not a policy id`);
        else if (mode !== 'enforce' && mode !== 'shadow') errors.push(`enforcement.${id} must be one of [enforce, shadow]`);
      }
  }
  const naming = content.naming;
  if (isObject(naming) && typeof naming.topicPattern === 'string') {
    try {
      new RegExp(naming.topicPattern);
    } catch {
      warnings.push('naming.topicPattern is not a valid regular expression (JavaScript syntax check)');
    }
  }
  return { errors, warnings };
}

/** Deep clone that keeps unknown keys; used to seed the editor from an existing version. */
export function cloneContent(
  content: PolicyConfigurationContent | Record<string, unknown>,
): PolicyConfigurationContent {
  return JSON.parse(JSON.stringify(content)) as PolicyConfigurationContent;
}

export const EMPTY_CONTENT: PolicyConfigurationContent = {
  naming: {
    topicPattern: '',
    requireTeamPrefix: true,
    teamPrefixSeparator: '.',
    environmentRestrictions: {},
  },
  rbac: { allowedRoles: [], forbiddenRoles: [], environmentRoles: {}, teamRoles: {}, sensitiveRoles: [] },
  limits: {
    maxTopicsPerManifest: 1,
    maxPartitionsPerTopic: 1,
    maxPartitionsPerPlan: 1,
    maxRetentionMs: 1,
    environmentOverrides: {},
  },
  destructive: {
    topicDeleteAllowedEnvironments: [],
    rbacRevokeAllowedEnvironments: [],
    identityPoolDeleteAllowedEnvironments: [],
  },
  approvals: {
    productionTopicDeletion: true,
    highImpactPartitionThreshold: 1,
    sensitiveRoleAssignment: true,
    sensitiveEnvironments: [],
  },
};
