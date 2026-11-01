import { UserManager, WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts';
import type { UiConfig } from '@/config';

/**
 * Authorization Code + PKCE. When `oidcEndpointBase` is set the endpoints are addressed through a same-origin
 * proxy (local compose: the browser cannot resolve the issuer host) while `issuer` stays what Governance validates.
 */
export function userManagerSettings(cfg: UiConfig, origin = window.location.origin): UserManagerSettings {
  const base = cfg.oidcEndpointBase ? origin + cfg.oidcEndpointBase.replace(/\/$/, '') : undefined;
  return {
    authority: cfg.oidcIssuer,
    client_id: cfg.oidcClientId,
    redirect_uri: origin + '/callback',
    post_logout_redirect_uri: origin + '/',
    response_type: 'code',
    scope: cfg.oidcScope,
    loadUserInfo: false,
    automaticSilentRenew: true,
    monitorSession: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    metadata: base
      ? {
          issuer: cfg.oidcIssuer,
          authorization_endpoint: base + '/authorize',
          token_endpoint: base + '/token',
          jwks_uri: base + '/jwks',
          end_session_endpoint: base + '/endsession',
          userinfo_endpoint: base + '/userinfo',
        }
      : undefined,
  };
}

let manager: UserManager | null = null;
export function getUserManager(cfg: UiConfig): UserManager {
  if (!manager) manager = new UserManager(userManagerSettings(cfg));
  return manager;
}
