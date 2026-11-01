# ADR 0006: Admin UI as a separate static application behind a same-origin proxy
The admin console is a React SPA in its own module (`admin-ui/`) and container, not static resources inside the
Governance jar. Reasons: the Java reactor stays Node-free (`mvn verify` unchanged), the UI can ship on its own
cadence, and the deployment keeps one container per service. nginx proxies `/api/**` to Governance and `/oidc/**`
to the identity provider, so the browser is same-origin: Governance gains no CORS surface and `/internal/opa/**`
stays unreachable. Sign-in is Authorization Code + PKCE; locally the proxy forces the issuer host on `/oidc/**`
because the mock provider derives `iss` from the request host and Governance validates the in-network issuer.
The UI reads roles from the token only to hide controls; authorization remains entirely server-side. One backend
addition came with it: `GET /api/v1/admin/policies` relays the bundle's own catalogue (`catalog.rego`) so the
exception form can refuse policies that are not bypassable, keeping Rego the single source of truth for policy ids.
