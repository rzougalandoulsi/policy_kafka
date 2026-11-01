# Admin UI

Browser console for operating the Governance service without curl or Swagger. It is a static React
application (`admin-ui/`) served by nginx; every call goes to the existing Governance REST API, which remains
the only enforcement point. There is no UI-specific backend state.

## What it covers
| Area | Page | API used |
|---|---|---|
| Overview | `/` | active configuration/bundle/approval rule, `policy-bundles/diagnostics`, exceptions expiring soon, open dead letters, last audit entries |
| Teams | `/teams`, `/teams/{teamId}` | `GET/PUT/DELETE /api/v1/admin/teams/{teamId}`, `/history`, `/usage`: authorised prefixes, quotas, allowed roles, source badge; capacity per environment (confirmed, reserved, headroom), repositories, open reservations, history, audit |
| Shadow findings | `/shadow-findings` | `GET /api/v1/admin/shadow-findings` filtered by policy, team, environment; per-policy summary |
| Scoped exceptions | `/exceptions`, `/exceptions/{id}` | `GET/POST/DELETE /api/v1/admin/exceptions`, `GET /api/v1/admin/policies` (catalogue), `GET /api/v1/admin/audit?targetType=policy_exception` |
| Policy configuration (governance rules) | `/policy-configurations`, `/new`, `/{id}` | `GET/POST /api/v1/admin/policy-configurations`, `/{id}/activate`, `/rollback`; the editor has an `enforcement` tab to put a policy in shadow mode |
| Policy bundles (Rego) | `/policy-bundles` | `GET/POST /api/v1/admin/policy-bundles`, `/{id}/activate`, `/rollback`, `/diagnostics` |
| Approval rules | `/approval-rules` | `GET/POST /api/v1/admin/approval-configurations`, `/{id}/activate` |
| Audit and dead letters | `/audit` | `GET /api/v1/admin/audit`, `GET /api/v1/admin/dead-letters`, `POST /dead-letters/{id}/resolve` |

Deliberately absent: editing Rego in the browser (bundles are built and tested by `policies/build.sh` and
uploaded as artifacts), the approver inbox, request tracking and manifest submission.

## Roles
Identity comes from the OIDC access token (claims `sub`, `roles`, `teams`, `environments`; claim names
configurable). The UI reads the roles only to shape the screens:

| Role | In the UI |
|---|---|
| `governance-auditor` | read everything; write controls hidden |
| `governance-admin` | read and write (admins are auditors too) |
| neither | redirected to a "no governance role" page |

A hidden button is not a security boundary: Governance checks the same roles on every request and the UI
surfaces its `403 FORBIDDEN` answer as-is.

## Policy catalogue
`GET /api/v1/admin/policies` relays the merged catalogue `data.governance.core.policy_catalog`: every bundle under
`policies/bundles/` declares the policies it can emit, and `core` concatenates them with a `bundle` field. The
exception form uses it to offer only **bypassable** policies: an exception on a `HARD_DENY` policy (such as
`RBAC_ADMIN_ROLE_FORBIDDEN`) or on an approval requirement would be accepted by the API but never apply.
`policies/tests/catalog_test.rego` fails when a rule emits an id missing from the catalogue.

## Sign-in
Authorization Code + PKCE (`oidc-client-ts`). Two subtleties worth knowing:

1. **Same-origin proxying.** nginx (or the Vite dev server) proxies `/api/**` to Governance and `/oidc/**` to the
   identity provider. The browser never talks cross-origin, so Governance needs no CORS configuration and
   `/internal/opa/**` is never exposed.
2. **Issuer host.** Governance validates `iss = http://mock-oidc:8080/default` in local compose, a host the
   browser cannot resolve. The proxy forces `Host: mock-oidc:8080` towards the mock provider (it derives the
   issuer from the Host header) and the UI addresses the endpoints through `/oidc/default/...` while keeping
   `issuer` set to the value Governance expects (`OIDC_ENDPOINT_BASE`). With a real identity provider leave
   `OIDC_ENDPOINT_BASE` empty: endpoints then come from discovery.

`DEV_TOKEN_LOGIN=true` adds a "paste a token" form (`deploy/scripts/token.sh admin-1`). Keep it off anywhere
that is not a local sandbox.

## Configuration
Runtime values are injected as `/env.js` by the container entrypoint (`admin-ui/nginx/40-env-js.sh`), so one
image serves every environment.

| Variable | Default (compose) | Meaning |
|---|---|---|
| `GOVERNANCE_URL` | `http://governance:8080` | upstream for `/api/` |
| `OIDC_UPSTREAM_URL` / `OIDC_UPSTREAM_HOST` | `http://mock-oidc:8080` / `mock-oidc:8080` | upstream and forced Host for `/oidc/` |
| `OIDC_ISSUER` | `http://mock-oidc:8080/default` | must equal Governance's `OAUTH2_ISSUER_URI` |
| `OIDC_CLIENT_ID`, `OIDC_SCOPE` | `admin-1`, `openid governance` | public client |
| `OIDC_ENDPOINT_BASE` | `/oidc/default` | empty = use discovery |
| `ROLES_CLAIM`, `ADMIN_ROLE`, `AUDITOR_ROLE` | `roles`, `governance-admin`, `governance-auditor` | mirror `governance.security.*` |
| `DEV_TOKEN_LOGIN` | `true` locally, `false` in the image | paste-a-token login |

## Development
```bash
cd admin-ui
pnpm install
pnpm dev            # http://localhost:3000, proxies /api -> :8080 and /oidc -> :18089 (compose)
pnpm test           # Vitest + Testing Library + MSW
pnpm lint && pnpm build
scripts/gen-api.sh  # regenerate src/api/schema.d.ts from a running Governance to spot drift in src/api/types.ts
```
The API types in `src/api/types.ts` are maintained by hand against the Java records (small surface, no build-time
dependency on a running service); `gen-api.sh` produces the generated counterpart for comparison.

## Troubleshooting
| Symptom | Likely cause | Check |
|---|---|---|
| Sign-in loops or "Sign-in failed" | `OIDC_ISSUER` does not match the `iss` the provider mints, or `/oidc/` proxy misses the Host header | `curl -H 'Host: mock-oidc:8080' http://localhost:3000/oidc/default/.well-known/openid-configuration` → `issuer` must equal `OIDC_ISSUER` |
| Signed in but every call is 401 | Governance validates a different issuer than the token's `iss` | compare `OAUTH2_ISSUER_URI` and the token payload shown in the account menu |
| "No governance role" page | token has no `governance-admin`/`governance-auditor` in the roles claim | `deploy/oidc/config.json` mappings; `ROLES_CLAIM` |
| Exception form shows a free-text policy field | catalogue endpoint returned 503 (OPA unreachable or no active bundle) | `/policy-bundles` diagnostics banner |
| Bundle activation reports "mismatch" then "rolled back" | OPA did not load the revision within 90 s | OPA logs; the bundle is marked FAILED with the reason |
