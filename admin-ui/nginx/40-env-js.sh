#!/bin/sh
# Writes /usr/share/nginx/html/env.js from environment variables (runtime config; see src/config.ts).
set -eu
OUT=/usr/share/nginx/html/env.js
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
cat > "$OUT" <<JS
window.__GOVERNANCE_UI__ = {
  oidcIssuer: "$(esc "${OIDC_ISSUER:-}")",
  oidcClientId: "$(esc "${OIDC_CLIENT_ID:-}")",
  oidcScope: "$(esc "${OIDC_SCOPE:-openid}")",
  oidcEndpointBase: "$(esc "${OIDC_ENDPOINT_BASE:-}")",
  rolesClaim: "$(esc "${ROLES_CLAIM:-roles}")",
  adminRole: "$(esc "${ADMIN_ROLE:-governance-admin}")",
  auditorRole: "$(esc "${AUDITOR_ROLE:-governance-auditor}")",
  devTokenLogin: $( [ "${DEV_TOKEN_LOGIN:-false}" = "true" ] && echo true || echo false )
};
JS
echo "admin-ui: wrote $OUT (issuer=${OIDC_ISSUER:-unset}, client=${OIDC_CLIENT_ID:-unset})"
