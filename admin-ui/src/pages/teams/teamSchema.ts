import { z } from 'zod';
import { QUOTA_KEYS, type QuotaKey, type TeamConfiguration, type UpsertTeamBody } from '@/api/types';

/** Mirrors TeamConfigurationService.validate. */
export const teamSchema = z
  .object({
    teamId: z
      .string()
      .trim()
      .regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, 'Team id: letters, digits, dot, underscore, dash; at most 64 characters'),
    authorizedPrefixes: z
      .array(z.string().trim().min(1).max(128).regex(/^\S+$/, 'Prefixes cannot contain whitespace'))
      .max(50, 'At most 50 prefixes'),
    quotas: z.record(
      z.enum(QUOTA_KEYS),
      z.union([z.literal(''), z.coerce.number().int('Must be a whole number').min(0, 'Must be 0 or more')]),
    ),
    allowedRoles: z.array(z.string().trim().min(1)),
    environmentsJson: z.string(),
    source: z.enum(['governance', 'external']),
    sourceVersion: z.string().trim(),
    comment: z.string().trim().max(500),
  })
  .superRefine((f, ctx) => {
    if (f.source === 'external' && !f.sourceVersion)
      ctx.addIssue({ code: 'custom', path: ['sourceVersion'], message: 'Required when the source is external' });
    const env = parseEnvironments(f.environmentsJson);
    if (env === null)
      ctx.addIssue({ code: 'custom', path: ['environmentsJson'], message: 'Must be a JSON object of environment -> parameters' });
  });

export type TeamForm = z.infer<typeof teamSchema>;

export function parseEnvironments(text: string): Record<string, Record<string, unknown>> | null {
  if (!text.trim()) return {};
  try {
    const parsed = JSON.parse(text) as unknown;
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    for (const v of Object.values(parsed as Record<string, unknown>)) {
      if (typeof v !== 'object' || v === null || Array.isArray(v)) return null;
    }
    return parsed as Record<string, Record<string, unknown>>;
  } catch {
    return null;
  }
}

export function defaultsFrom(t: TeamConfiguration | undefined, teamId = ''): TeamForm {
  const quotas: TeamForm['quotas'] = {};
  for (const k of QUOTA_KEYS) quotas[k] = t?.quotas?.[k] ?? '';
  return {
    teamId: t?.teamId ?? teamId,
    authorizedPrefixes: t?.authorizedPrefixes ?? (teamId ? [`${teamId}.`] : []),
    quotas,
    allowedRoles: t?.allowedRoles ?? [],
    environmentsJson: t && Object.keys(t.environments ?? {}).length ? JSON.stringify(t.environments, null, 2) : '',
    source: t?.source === 'external' ? 'external' : 'governance',
    sourceVersion: t?.sourceVersion ?? '',
    comment: '',
  };
}

export function toBody(f: TeamForm): UpsertTeamBody {
  const quotas: Partial<Record<QuotaKey, number>> = {};
  for (const k of QUOTA_KEYS) {
    const v = f.quotas[k];
    if (v !== '' && v !== undefined) quotas[k] = v;
  }
  return {
    authorizedPrefixes: f.authorizedPrefixes.map((p) => p.toLowerCase()),
    quotas,
    allowedRoles: f.allowedRoles,
    environments: parseEnvironments(f.environmentsJson) ?? {},
    source: f.source,
    sourceVersion: f.source === 'external' ? f.sourceVersion : null,
    comment: f.comment || null,
  };
}

/** Plain-words summary of what a configuration means for evaluations. */
export function describeTeam(t: {
  authorizedPrefixes: string[];
  quotas: Partial<Record<QuotaKey, number>>;
  allowedRoles: string[];
}): string {
  const prefixes = t.authorizedPrefixes.length
    ? `Topics must start with ${t.authorizedPrefixes.map((p) => `"${p}"`).join(' or ')}.`
    : 'No authorised prefix: every topic is denied while the prefix rule is enforced.';
  const quotaEntries = Object.entries(t.quotas).filter(([, v]) => v !== undefined);
  const quotas = quotaEntries.length
    ? `Quotas: ${quotaEntries.map(([k, v]) => `${k} ≤ ${v}`).join(', ')} across all repositories per environment.`
    : 'No quotas: usage is unlimited.';
  const roles = t.allowedRoles.length
    ? `Only the roles ${t.allowedRoles.join(', ')} may be granted.`
    : 'Roles follow the global rbac configuration.';
  return `${prefixes} ${quotas} ${roles}`;
}
