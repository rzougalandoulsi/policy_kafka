/**
 * Runtime configuration. Precedence: window.__GOVERNANCE_UI__ (from /env.js, generated per container)
 * > VITE_* build-time variables > defaults that match deploy/docker-compose.yml.
 */
export interface UiConfig {
  /** Issuer exactly as Governance validates it (JWT `iss`). */
  oidcIssuer: string;
  oidcClientId: string;
  oidcScope: string;
  /** Same-origin prefix for OIDC endpoints when the issuer host is not reachable from the browser. */
  oidcEndpointBase?: string;
  rolesClaim: string;
  adminRole: string;
  auditorRole: string;
  /** Dev-only paste-a-token login. Never enable against a real identity provider. */
  devTokenLogin: boolean;
}

const env = import.meta.env;

function flag(v: string | boolean | undefined, fallback: boolean): boolean {
  if (typeof v === 'boolean') return v;
  if (v == null || v === '') return fallback;
  return v === 'true' || v === '1';
}

export function loadConfig(overrides: Partial<UiConfig> | undefined = window.__GOVERNANCE_UI__): UiConfig {
  const o = overrides ?? {};
  return {
    oidcIssuer: o.oidcIssuer ?? env.VITE_OIDC_ISSUER ?? 'http://mock-oidc:8080/default',
    oidcClientId: o.oidcClientId ?? env.VITE_OIDC_CLIENT_ID ?? 'admin-1',
    oidcScope: o.oidcScope ?? env.VITE_OIDC_SCOPE ?? 'openid governance',
    oidcEndpointBase: emptyToUndefined(o.oidcEndpointBase ?? env.VITE_OIDC_ENDPOINT_BASE),
    rolesClaim: o.rolesClaim ?? env.VITE_ROLES_CLAIM ?? 'roles',
    adminRole: o.adminRole ?? env.VITE_ADMIN_ROLE ?? 'governance-admin',
    auditorRole: o.auditorRole ?? env.VITE_AUDITOR_ROLE ?? 'governance-auditor',
    devTokenLogin: flag(o.devTokenLogin ?? env.VITE_DEV_TOKEN_LOGIN, false),
  };
}

function emptyToUndefined(v: string | undefined): string | undefined {
  return v == null || v.trim() === '' ? undefined : v;
}

export const config: UiConfig = loadConfig();
