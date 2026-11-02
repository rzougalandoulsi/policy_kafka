#!/bin/sh
# Writes env.js from environment variables (runtime config; see src/config.ts). ENV_JS_PATH defaults to the html
# directory (compose); OpenShift sets it to a writable emptyDir so the root filesystem can stay read-only.
set -eu
OUT="${ENV_JS_PATH:-/usr/share/nginx/html/env.js}"
mkdir -p "$(dirname "$OUT")"
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
  devTokenLogin: $( [ "${DEV_TOKEN_LOGIN:-false}" = "true" ] && echo true || echo false ),
  localPolicyAdmin: $( [ "${LOCAL_POLICY_ADMIN:-true}" = "true" ] && echo true || echo false )
};
JS
echo "admin-ui: wrote $OUT (issuer=${OIDC_ISSUER:-unset}, client=${OIDC_CLIENT_ID:-unset})"
