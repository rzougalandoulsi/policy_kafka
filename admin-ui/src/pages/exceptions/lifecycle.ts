import type { PolicyException } from '@/api/types';
import type { BadgeTone } from '@/components/ui/badge';

export type ExceptionLifecycle = 'active' | 'expiring' | 'expired' | 'revoked';

export const EXPIRING_SOON_MS = 7 * 24 * 3_600_000;

export function lifecycleOf(
  e: Pick<PolicyException, 'revokedAt' | 'expiresAt'>,
  now: Date = new Date(),
): ExceptionLifecycle {
  if (e.revokedAt) return 'revoked';
  const expires = new Date(e.expiresAt).getTime();
  if (expires <= now.getTime()) return 'expired';
  if (expires - now.getTime() <= EXPIRING_SOON_MS) return 'expiring';
  return 'active';
}

export const LIFECYCLE_LABEL: Record<ExceptionLifecycle, string> = {
  active: 'Active',
  expiring: 'Expiring soon',
  expired: 'Expired',
  revoked: 'Revoked',
};

export const LIFECYCLE_TONE: Record<ExceptionLifecycle, BadgeTone> = {
  active: 'ok',
  expiring: 'warn',
  expired: 'neutral',
  revoked: 'danger',
};
