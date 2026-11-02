// Development defaults. In the container this file is generated from environment variables
// (see nginx/40-env-js.sh); keys left undefined fall back to VITE_* build-time values.
window.__GOVERNANCE_UI__ = {
  oidcIssuer: 'http://mock-oidc:8080/default',
  oidcClientId: 'admin-1',
  oidcScope: 'openid governance',
  oidcEndpointBase: '/oidc/default',
  rolesClaim: 'roles',
  adminRole: 'governance-admin',
  auditorRole: 'governance-auditor',
  devTokenLogin: true,
  localPolicyAdmin: true,
};
