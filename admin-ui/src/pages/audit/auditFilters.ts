import type { AuditQuery } from '@/api/types';

export const LIMIT_OPTIONS = [50, 200, 500, 1000] as const;
export const DEFAULT_LIMIT = 200;

export interface AuditFilters {
  targetType: string;
  targetId: string;
  requestId: string;
  actor: string;
  /** ISO instant or empty. */
  since: string;
  limit: number;
}

export function filtersFromSearch(sp: URLSearchParams): AuditFilters {
  const limit = Number(sp.get('limit'));
  return {
    targetType: sp.get('targetType') ?? '',
    targetId: sp.get('targetId') ?? '',
    requestId: sp.get('requestId') ?? '',
    actor: sp.get('actor') ?? '',
    since: sp.get('since') ?? '',
    limit: (LIMIT_OPTIONS as readonly number[]).includes(limit) ? limit : DEFAULT_LIMIT,
  };
}

export function searchFromFilters(f: AuditFilters): URLSearchParams {
  const sp = new URLSearchParams();
  for (const k of ['targetType', 'targetId', 'requestId', 'actor', 'since'] as const) {
    if (f[k]) sp.set(k, f[k]);
  }
  if (f.limit !== DEFAULT_LIMIT) sp.set('limit', String(f.limit));
  return sp;
}

export function toQuery(f: AuditFilters): AuditQuery {
  return {
    targetType: f.targetType || undefined,
    targetId: f.targetId || undefined,
    requestId: f.requestId || undefined,
    actor: f.actor || undefined,
    since: f.since || undefined,
    limit: f.limit,
  };
}

/** Where an audit target lives in this console, if anywhere. */
export function targetLink(targetType: string, targetId: string): string | null {
  switch (targetType) {
    case 'policy_exception':
      return `/exceptions/${encodeURIComponent(targetId)}`;
    case 'policy_configuration':
      return `/policy-configurations/${encodeURIComponent(targetId)}`;
    case 'policy_bundle':
      return '/policy-bundles';
    case 'approval_configuration':
      return '/approval-rules';
    case 'kafka_dead_letter':
      return '/audit#dead-letters';
    default:
      return null;
  }
}

export function actionTone(action: string): 'ok' | 'warn' | 'danger' | 'info' | 'neutral' {
  if (/REVOKED|FAILED|REJECTED|EXPIRED|CANCELLED/.test(action)) return 'danger';
  if (/ROLLED_BACK|RESOLVED/.test(action)) return 'warn';
  if (/ACTIVATED|APPROVED|COMPLETED/.test(action)) return 'ok';
  if (/CREATED|UPLOADED|OPENED|DECISION/.test(action)) return 'info';
  return 'neutral';
}
