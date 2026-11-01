/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_ISSUER?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  readonly VITE_OIDC_ENDPOINT_BASE?: string;
  readonly VITE_ROLES_CLAIM?: string;
  readonly VITE_ADMIN_ROLE?: string;
  readonly VITE_AUDITOR_ROLE?: string;
  readonly VITE_DEV_TOKEN_LOGIN?: string;
}

interface Window {
  __GOVERNANCE_UI__?: Partial<import('./config').UiConfig>;
}
