# Plan: Governance Admin UI

**Source**: `/ecc:plan` free-form requirements ("a UI admin dashboard for managing policies and governance rules, exceptions")
**Complexity**: Medium–Large
**Status**: IMPLEMENTED (2026-11-01). Deviation: API types are hand-maintained in `admin-ui/src/api/types.ts` (no running service at build time); `scripts/gen-api.sh` produces the generated counterpart for drift checks.

---

## 1. Requirements restatement

Build a browser admin console for the existing Governance service so that administrators and auditors can
operate it without curl/Swagger. It must cover, read-only for auditors and read-write for admins:

| Area | Backend already exposes | UI must offer |
|---|---|---|
| **Policy configuration** (governance rules: naming, rbac, limits, destructive, approvals) | `/api/v1/admin/policy-configurations` list/get/create draft/activate/rollback | Version list with status, structured editor per section + raw JSON, diff between versions, activate/rollback with confirmation |
| **Policy bundles** (Rego logic) | `/api/v1/admin/policy-bundles` list/get/upload/activate/rollback/`diagnostics` | Upload tar.gz + `opa test` JSON report, version list (revision, sha256, tests passed, status, failure reason), activate/rollback, live "active vs OPA revision" banner |
| **Scoped exceptions** | `/api/v1/admin/exceptions` list(teamId, includeInactive)/get/create/revoke | Filterable list with lifecycle badges (active / expiring soon / expired / revoked), guided create form (bypassable policies only), revoke with reason, per-exception audit trail |
| **Approval rules** | `/api/v1/admin/approval-configurations` list/create/activate | Current quorum/roles/SoD/TTL card, new version form, activate |
| **Audit** | `/api/v1/admin/audit` (targetType, targetId, requestId, actor, since, limit) | Filterable table; deep links from every entity |
| **Overview** | `diagnostics`, active config/bundle/approval config, `/api/v1/admin/dead-letters` | Health cards: active revisions, OPA match, open dead letters, exceptions expiring in 7 days |

Out of scope for this plan (can be follow-ups): approver inbox (deciding approvals), request tracking UI,
manifest submission UI, editing Rego in the browser (explicitly forbidden by design: bundles are built by `policies/build.sh`).

Identity: login via the same OIDC issuer the API trusts (OAuth2 Authorization Code + PKCE). Roles are read from the
JWT `roles` claim to shape the UI; the API remains the enforcement point (403 → clear message).

## 2. Patterns to mirror (grounding)

| Category | Source | Pattern |
|---|---|---|
| API surface | `governance/src/main/java/com/itland/governance/api/administration/*Controller.java` | One controller per aggregate under `/api/v1/admin/<plural>`; read = auditor, write = admin; action verbs as `POST /{id}/activate`, `POST /rollback`, `DELETE /{id}?reason=` |
| DTO shape | `ExceptionAdminController.CreateBody`, `PolicyConfigurationAdminController.CreateBody` | Java records with Bean Validation; UI forms mirror these field-for-field |
| Error contract | `api/ApiError.java`, `api/ApiExceptionHandler.java` | `{code, message, timestamp, errors[], details{}}`; codes `BAD_REQUEST`, `FORBIDDEN`, `NOT_FOUND`, `STATE_CONFLICT`, `INTERNAL_ERROR`; `errors[]` holds `field: message` strings → map to inline field errors |
| Domain validation to mirror client-side | `PolicyConfigurationService.validate` (required object sections `naming,rbac,limits,destructive,approvals`; `limits.*` positive numbers), `ExceptionAdminService.create` (policyId `^[A-Z][A-Z0-9_]{2,63}$`, reason required, expiresAt in future and ≤ 365 days, env/resourcePattern default `*`) | Validate before submit, still surface server `errors[]` |
| Ids / naming | `cfg-`, `bundle-`, `exc-`, `apr-` UUID prefixes; `VersionStatus {DRAFT, UPLOADED, ACTIVE, RETIRED, FAILED}` | Badge colours keyed on these exact enums |
| Security model | `infrastructure/security/SecurityConfig.java`, `TeamAuthorization.java`, `CallerContextResolver.java` | JWT only; roles claim → `governance-admin` / `governance-auditor` (admin implies auditor). No CORS config exists today |
| Identity provider (local) | `deploy/oidc/config.json`, `deploy/scripts/token.sh` | mock-oauth2-server, `interactiveLogin:false`, claims keyed by `client_id` (`admin-1` has admin+auditor); issuer `http://mock-oidc:8080/default` inside compose |
| Audit | `application/audit/AuditService.java`, `AuditController` | Actions `POLICY_CONFIGURATION_*`, `POLICY_BUNDLE_*`, `EXCEPTION_*`, `APPROVAL_CONFIGURATION_*`; target types `policy_configuration`, `policy_bundle`, `policy_exception`, `approval_configuration` |
| Policy catalogue | `policies/governance/*.rego` (`h.violation("<ID>", "<SEVERITY>", …, bypassable)`) | 14 policy ids today; only `RBAC_ADMIN_ROLE_FORBIDDEN` is HARD_DENY / non-bypassable |
| Deployment | `deploy/docker-compose.yml`, `governance/Dockerfile` | One container per service, env-driven config, healthchecks |
| Tests (backend) | JUnit 5 + WebTestClient; `*IT` under failsafe with Testcontainers (`GovernanceEndToEndIT`) | New backend endpoint gets a unit test + an assertion in the existing IT |
| Tests (frontend) | **None exist in this repo.** No frontend code at all. | We introduce Vitest + Testing Library + MSW; stated explicitly, not inherited |
| Docs | `README.md` service table, `docs/governance.md`, `docs/runbook.md`, `docs/adr/000N-*.md` | Add a row, a doc page, and an ADR for the UI architecture decision |

## 3. Key design decisions (recommendations)

1. **Separate module `admin-ui/`, not embedded in the Spring Boot jar.** Keeps the Java reactor unchanged (`mvn verify` stays Node-free), lets the UI ship on its own cadence, and matches the one-container-per-service layout. Not added to the Maven reactor.
2. **Stack**: Vite + React 18 + TypeScript (strict), TanStack Query (server state, polling for diagnostics), React Router, react-hook-form + zod (mirrors Bean Validation), Tailwind + shadcn/ui (accessible table/dialog/form primitives), `oidc-client-ts` for PKCE, `openapi-typescript` to generate API types from the service's own `/v3/api-docs` (snapshot committed as `admin-ui/openapi.json`, refresh script provided).
3. **Served by nginx**; nginx reverse-proxies `/api/**` → `governance:8080` so the browser is same-origin and **no CORS change is needed in Governance**. The `/internal/opa/**` path is deliberately *not* proxied.
4. **Auth = Authorization Code + PKCE** against the configured issuer; access token kept in memory (session storage for refresh across reloads), attached as `Authorization: Bearer`. Role gating in UI from the `roles` claim (claim name configurable via `VITE_ROLES_CLAIM`, default `roles`). Everything stays enforced server-side.
5. **One small backend addition: `GET /api/v1/admin/policies`** (auditor) returning the policy catalogue `{policyId, severity, bypassable, section}` so the exception form can only offer bypassable policies (an exception on a HARD_DENY policy would be silently useless). Source of truth stays Rego: add `catalog.rego` to the bundle exposing `data.governance.catalog`, Governance proxies it from OPA. Falls back to an empty list + free-text input if OPA is unavailable.
6. **No pagination added** to list endpoints for v1 (volumes are small: versions, exceptions). Client-side filtering/sorting; `limit` passed where the API supports it. Noted as a follow-up if needed.

## 4. Files to change

| File | Action | Why |
|---|---|---|
| `admin-ui/` (package.json, vite.config.ts, tsconfig, tailwind, index.html, `src/**`) | CREATE | The SPA |
| `admin-ui/openapi.json` + `admin-ui/scripts/gen-api.sh` | CREATE | Typed client generated from Governance's OpenAPI |
| `admin-ui/Dockerfile`, `admin-ui/nginx.conf` | CREATE | Static serving + `/api` reverse proxy |
| `admin-ui/.env.example` | CREATE | `VITE_OIDC_ISSUER`, `VITE_OIDC_CLIENT_ID`, `VITE_ROLES_CLAIM` |
| `deploy/docker-compose.yml` | UPDATE | Add `admin-ui` service on `:3000`, depends on `governance` |
| `deploy/oidc/config.json` | UPDATE | Allow redirect to `http://localhost:3000/callback` for `admin-1` / `approver-1` (auth-code flow) |
| `policies/governance/catalog.rego` (+ `catalog_test.rego`) | CREATE | Policy catalogue exposed by OPA |
| `governance/.../api/administration/PolicyCatalogController.java` | CREATE | `GET /api/v1/admin/policies` (auditor) |
| `governance/.../infrastructure/opa/OpaClient.java` | UPDATE | `catalog()` query to `/v1/data/governance/catalog` |
| `governance/.../e2e/GovernanceEndToEndIT.java` | UPDATE | Assert catalogue lists 14 ids and flags the HARD_DENY one |
| `.gitignore` | UPDATE | `admin-ui/node_modules/`, `admin-ui/dist/` |
| `README.md`, `docs/admin-ui.md`, `docs/runbook.md`, `docs/adr/0006-admin-ui-separate-spa.md` | UPDATE/CREATE | Build/run instructions, roles, troubleshooting, decision record |

## 5. Phases and tasks

### Phase 1 — Foundation (auth, shell, typed client) · ~5h
1. Scaffold `admin-ui/` (Vite React-TS, Tailwind, shadcn/ui, ESLint/Prettier, Vitest + Testing Library + MSW).
2. OIDC PKCE login (`oidc-client-ts`): `/callback` route, silent renew, logout, "signed in as X (roles)" header; token injected by a single `apiFetch` wrapper that parses `ApiError` and raises typed errors.
3. Generate API types from `openapi.json`; commit snapshot and `gen-api.sh` (curls a running service).
4. App shell: left nav (Overview, Policy configuration, Policy bundles, Exceptions, Approval rules, Audit), role gate (`RequireRole admin|auditor`), global error toast mapping `ApiError.code`, 401 → re-login, 403 → "requires governance-admin".
- **Validate**: `pnpm test` (auth store, apiFetch error mapping with MSW), `pnpm build`, manual login against compose.

### Phase 2 — Exceptions (highest operational value) · ~6h
1. List page: filters teamId / includeInactive / policyId / status; computed badge (active, expires < 7d, expired, revoked); columns policy, team, env, principal, pattern, actions, expiresAt, createdBy.
2. Create drawer: policy picker fed by `GET /api/v1/admin/policies` (bypassable only; HARD_DENY shown disabled with explanation), team, environment (`*` default), optional principal, resource glob with live sample matcher, action types multi-select (`TOPIC_CREATE, TOPIC_DELETE, RBAC_GRANT, RBAC_REVOKE, IDENTITY_POOL_CREATE, IDENTITY_POOL_DELETE` + free text), expiresAt date-time picker capped at +365d with presets (24h / 7d / 30d / 90d), reason (required). zod schema mirrors `ExceptionAdminService`.
3. Detail page: full record + audit entries for `targetType=policy_exception&targetId=<id>`; revoke dialog (reason, idempotent).
- **Validate**: component tests (form validation parity cases, revoke idempotency), MSW handlers from real response shapes.

### Phase 3 — Policy configuration (governance rules) · ~7h
1. Versions table (version, status, comment, createdBy/At, activatedAt, retiredAt); exactly one ACTIVE highlighted.
2. Editor for a new draft: tabbed sections `naming | rbac | limits | destructive | approvals` with typed fields for every key present in `V2__policy_management.sql` seed, plus "Raw JSON" tab (CodeMirror) kept in sync; "start from version N". Client validation = `PolicyConfigurationService.validate`.
3. Diff view (JSON diff, side-by-side) between any two versions; shown automatically in the Activate confirmation ("activating v7 retires v6; changes: …").
4. Activate / Rollback with confirmation; after mutation refetch list and Overview.
- **Validate**: tests for section ↔ JSON round-trip, validation parity, diff rendering; manual activate/rollback against compose and check `audit_record`.

### Phase 4 — Policy bundles + Overview · ~5h
1. Bundles table (revision, status, sha256 short, tests passed, uploadedBy/At, activatedAt, failureReason).
2. Upload dialog: two file inputs (tar.gz → base64, test-report.json → string), comment; shows parsed `.manifest.revision` client-side before submit (gzip/tar read in browser) and the test count.
3. Activate / Rollback; after activate, poll `diagnostics` every 3s for up to 90s and show adopt/mismatch/auto-rollback outcome (mirrors `verifyActivation` grace).
4. Overview page: active config version, active bundle revision vs OPA revision (match badge), active approval rule summary, open dead letters count, exceptions expiring ≤ 7d, last 10 audit entries.
- **Validate**: tests for base64/manifest parsing and polling state machine; manual run of `deploy/scripts/upload-bundle.sh` equivalence through the UI.

### Phase 5 — Approval rules + Audit + backend catalogue · ~5h
1. Approval rules: active card + history; create form (approverRoles chips, requiredApprovals 1–10, SoD toggle, ttlHours 1–720) mirroring `ApprovalConfigurationAdminController.CreateBody`; activate.
2. Audit explorer: filters targetType/targetId/requestId/actor/since/limit; JSON details expander; shareable URL state.
3. Backend: `catalog.rego` + test, `OpaClient.catalog()`, `PolicyCatalogController`, IT assertion. Governance unit test for the controller's auditor gate.
- **Validate**: `mvn -q verify` (includes IT; Docker required), `policies/test.sh`, `pnpm test`.

### Phase 6 — Packaging, docs, hardening · ~4h
1. `admin-ui/Dockerfile` (node build stage → nginx), `nginx.conf` (SPA fallback, `/api` proxy, security headers: CSP, no-sniff, frame-deny), compose service, `.env.example`.
2. mock-oidc: add redirect URIs / confirm auth-code flow works end-to-end with `admin-1`; document the dev client ids and which roles they carry.
3. Docs: README row + run instructions, `docs/admin-ui.md`, runbook section (login fails / 403 / OPA mismatch banner), ADR 0006.
4. Accessibility pass (keyboard nav on tables and dialogs, labels, contrast), empty/loading/error states on every page.
- **Validate**: `docker compose up --build`, login, full click-through of all six areas; `pnpm build` size budget; Lighthouse a11y ≥ 90.

## 6. Validation commands

```bash
# frontend
cd admin-ui && pnpm install && pnpm lint && pnpm test && pnpm build
# backend (catalogue endpoint) + Rego
mvn -q verify
policies/test.sh && policies/build.sh
# full stack
docker compose -f deploy/docker-compose.yml up --build -d && deploy/scripts/upload-bundle.sh
open http://localhost:3000   # login as admin-1
```

## 7. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **OIDC issuer mismatch in local compose**: Governance validates `iss = http://mock-oidc:8080/default`, but a browser reaches the mock on `localhost:18089`, and mock-oauth2-server derives `iss` from the request host | High | Login works but API returns 401 | nginx also proxies `/oidc/**` → `mock-oidc:8080` with `proxy_set_header Host mock-oidc:8080`, and the UI overrides OIDC metadata endpoints to the proxied paths. Fallback: dev-only "paste token" login behind `VITE_DEV_TOKEN_LOGIN=true`, fed by `deploy/scripts/token.sh`. Real IdPs have a fixed issuer, so this is local-only |
| Auth-code flow not enabled for mock clients (`interactiveLogin:false` + `client_id` mappings were made for client-credentials) | Medium | Blocked login | Verify in Phase 1 first task; mock-oauth2-server supports auth-code without a login page and maps claims by `client_id` |
| Exception created for a non-bypassable policy never applies (silent no-op) | Medium | Operator confusion | Policy catalogue endpoint + disabled HARD_DENY entries in the picker |
| Bundle upload size (base64 in JSON body) exceeds WebFlux default max in-memory size (256KB) | Medium | 413 on upload | Current bundle is small; set `spring.codec.max-in-memory-size` if needed (document in runbook); UI shows size before upload |
| OpenAPI snapshot drifts from the service | Medium | Type errors hide at runtime | `gen-api.sh` + CI step that regenerates and fails on diff (follow-up) |
| Config editor lets admins activate a syntactically valid but semantically wrong rule set | Low–Medium | Policy outage | Diff shown at activation, rollback one click away, audit trail; typed fields for known keys |
| Token in browser storage | Low | XSS → token theft | Memory-first storage, strict CSP via nginx, no inline scripts, short-lived access tokens |
| Scope creep into approvals / requests UI | Medium | Schedule | Explicitly out of scope; listed as follow-ups |

## 8. Estimate

| Phase | Hours |
|---|---|
| 1 Foundation | 5 |
| 2 Exceptions | 6 |
| 3 Policy configuration | 7 |
| 4 Bundles + Overview | 5 |
| 5 Approval rules + Audit + catalogue endpoint | 5 |
| 6 Packaging, docs, hardening | 4 |
| **Total** | **~32h** |

## 9. Acceptance

- [ ] Admin can log in via OIDC, see role badge, and reach all six areas; auditor sees read-only views and every write control is hidden/disabled
- [ ] Exceptions: create (bypassable policies only), list/filter, revoke, view audit trail
- [ ] Policy configuration: create draft (form + JSON), diff versions, activate, rollback
- [ ] Bundles: upload tar.gz + report, activate, rollback, live OPA revision match banner
- [ ] Approval rules: create version and activate
- [ ] Audit explorer with all API filters and deep links from every entity
- [ ] `GET /api/v1/admin/policies` covered by unit test + IT; `mvn -q verify` and `policies/test.sh` green
- [ ] `pnpm lint && pnpm test && pnpm build` green; compose `admin-ui` service healthy on `:3000`
- [ ] README, `docs/admin-ui.md`, runbook and ADR 0006 updated
