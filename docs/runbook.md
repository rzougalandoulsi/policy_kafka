# Runbook

## Health
- `GET /actuator/health/readiness` (groups `db`, `opa`); `GET /actuator/health/liveness`.

## Key metrics (Prometheus, `/actuator/prometheus`)
| Metric | Meaning / alert |
|---|---|
| `governance_opa_evaluation_seconds{stage,outcome}` | OPA latency; `outcome=ERROR` rising ⇒ OPA or bundle problem |
| `governance_decisions_total{stage,type}` | decision mix |
| `governance_policy_revision_match` | 1 OK, 0 OPA runs a different revision than ACTIVE, -1 unknown |
| `governance_outbox_pending` / `governance_outbox_failed` | Kafka publishing backlog / permanently failed commands (alert on failed > 0) |
| `governance_inbox_retry` / `governance_kafka_deadletters_open` | events awaiting retry / dead letters (alert on open > 0) |
| `governance_approvals_pending` | human backlog |
| `kafka_consumer_fetch_manager_records_lag_max` | consumer lag on `response.manifest` |

## Diagnosing a request
1. `GET /api/v1/requests/{id}` → status, `failureReason`, history with event ids.
2. `GET /api/v1/requests/{id}/decisions` → every OPA decision with `policyRevision`, `configurationVersion`, violations, applied exceptions.
3. `GET /api/v1/admin/audit?requestId={id}`.
4. Logs carry `requestId`/`planId` (MDC) and `X-Correlation-Id` for HTTP calls.

## Failure modes
| Symptom | Cause | Action |
|---|---|---|
| 503 `POLICY_EVALUATION_UNAVAILABLE` | OPA down, no ACTIVE bundle, revision mismatch | check `/api/v1/admin/policy-bundles/diagnostics`, OPA logs; clients retry with the same `Idempotency-Key` |
| Request stuck in `SUBMITTED` | Orchestrator (or stand-in) down / outbox not relayed | `governance_outbox_pending`, Orchestrator logs |
| Request `EVALUATION_ERROR` after PLAN_PROPOSED | stage 2 OPA failure; inbox retries (`max-evaluation-attempts`) then REJECT_PLAN is sent | fix OPA; resubmit |
| `STALE_PLAN` | confirmed state moved while an approval was pending | resubmit the manifest (replan) |
| Dead letters | poison record or handler bug | `GET /api/v1/admin/dead-letters`, fix, `POST /api/v1/admin/dead-letters/{id}/resolve` |
| Bundle `FAILED` | OPA could not load it; previous version restored automatically | fix the bundle, upload a new revision |

## Policy rollout
`policies/build.sh <revision>` → `POST /api/v1/admin/policy-bundles` (bundle + test report) → `POST .../activate` → watch `diagnostics.match` → `POST /api/v1/admin/policy-bundles/rollback` if needed. Configuration changes: create a draft, activate, rollback.
The same steps are available in the admin console (`/policy-bundles` upload + activate with a live adoption watcher, `/policy-configurations` with a diff before activation). See `docs/admin-ui.md`.

## Admin console
| Symptom | Cause | Action |
|---|---|---|
| Sign-in fails / loops | `OIDC_ISSUER` differs from the `iss` the provider mints, or the `/oidc/` proxy does not force the issuer Host | `docs/admin-ui.md` → Troubleshooting |
| Signed in, every call 401 | Governance validates another issuer than the token | compare `OAUTH2_ISSUER_URI` with the token in the account menu |
| "No governance role" | token lacks `governance-admin` / `governance-auditor` | identity provider role mapping, `ROLES_CLAIM` |
| Exception form falls back to free text | catalogue 503: OPA down or no active bundle | diagnostics banner on `/policy-bundles` |

## Security notes
- OPA has no published port; its bundle endpoint on Governance is guarded by `OPA_BUNDLE_TOKEN` and must stay off the public network.
- Kafka credentials per service (`deploy/kafka/acls.sh`): only `governance` can write `requests.manifest`.
- Secrets via environment (`deploy/.env.example`); use TLS listeners (`SASL_SSL`) and a real issuer in production.
