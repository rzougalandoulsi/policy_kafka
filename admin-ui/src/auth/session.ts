import type { UiConfig } from '@/config';
import { decodeJwtPayload, stringSet, type Claims } from './claims';

export type SessionSource = 'oidc' | 'token';

/** What the UI knows about the caller; the API re-derives all of this from the JWT itself. */
export interface Session {
  accessToken: string;
  source: SessionSource;
  principalId: string;
  roles: string[];
  teams: string[];
  environments: string[];
  expiresAt: Date | null;
  claims: Claims;
}

export function sessionFromToken(accessToken: string, source: SessionSource, cfg: UiConfig): Session | null {
  const claims = decodeJwtPayload(accessToken);
  if (!claims) return null;
  return {
    accessToken,
    source,
    principalId: typeof claims.sub === 'string' ? claims.sub : 'unknown',
    roles: stringSet(claims[cfg.rolesClaim]),
    teams: stringSet(claims['teams']),
    environments: stringSet(claims['environments']),
    expiresAt: claims.exp ? new Date(claims.exp * 1000) : null,
    claims,
  };
}

export interface Entitlements {
  isAdmin: boolean;
  /** Admins are auditors too (TeamAuthorization.isAuditor). */
  isAuditor: boolean;
  canRead: boolean;
  canWrite: boolean;
}

export function entitlements(session: Session | null, cfg: UiConfig): Entitlements {
  const isAdmin = !!session?.roles.includes(cfg.adminRole);
  const isAuditor = isAdmin || !!session?.roles.includes(cfg.auditorRole);
  return { isAdmin, isAuditor, canRead: isAuditor, canWrite: isAdmin };
}
