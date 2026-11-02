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
| `governance_shadow_findings_total{policyId,kind}` | what a shadow-mode policy would have denied / sent to approval; the soak evidence before flipping it to enforce |
| `governance_usage_snapshots_total{outcome}` / `governance_usage_stale_total` | confirmed-state records consumed from `teams.repo.manifest` / stage 2 evaluations deferred because the snapshot lagged the plan (alert when `stale` keeps rising: the usage consumer is behind or the topic is unreadable) |
| `governance_quota_reservations_open`, `governance_quota_reservations_total{outcome}` | capacity held by authorised plans still in flight; `EXPIRED` rising means plans are authorised but never reported back |
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
| Stage 2 `EVALUATION_ERROR` "Usage snapshot ... older than the plan" | Governance has not consumed the confirmed state the plan was computed against (`teams.repo.manifest` lag, missing read ACL, consumer disabled) | check the usage consumer logs/lag, `governance_usage_snapshots_total`; the inbox retries by itself; `GOVERNANCE_USAGE_REQUIRE_FRESH_SNAPSHOT=false` disables the guard (quotas may then undercount) |
| `TEAM_QUOTA_EXCEEDED` although the team "has room" | reservations of in-flight plans count; or confirmed usage includes another repository of the team | `GET /api/v1/admin/teams/{id}/usage` (capacity per environment, repositories, open reservations) |
| 409 `TEAM_CONFIGURATION_EXTERNAL` on a team edit | the team is owned by an external registry | edit the registry, or save with `source=governance` to take ownership (audited) |
| Dead letters | poison record or handler bug | `GET /api/v1/admin/dead-letters`, fix, `POST /api/v1/admin/dead-letters/{id}/resolve` |
| Bundle `FAILED` | OPA could not load it; previous version restored automatically | fix the bundle, upload a new revision |

## Policy rollout
Two modes (ADR 0008). **Deployed environments run in `release` mode**: the policy set is pinned by
`deploy/helm/governance/releases/<env>.json`, OPA pulls the signed revisions from Artifactory, upload/activate/rollback
answer 409 `POLICY_SOURCE_IS_GIT`, and the console shows the release read-only. Promote with Workflow C (release PR),
roll back with Workflow E; `helm upgrade` rolls the pods and each pod is ready only when its sidecar runs the whole set
(`GET /api/v1/admin/policy-releases/current` shows `mode`, `source`, expected versus loaded per bundle).
`deploy/scripts/post-deploy-check.sh` runs the per-pod check after a deploy. A pod that never becomes ready: `oc logs
<pod> -c opa` (download, signature or activation error), then the readiness details. **Local mode** (Compose, IT):
`policies/build.sh <revision>` builds six bundles and `dist/release.json` → upload each (`POST /api/v1/admin/policy-bundles`, bundle + shared test report) → `POST /api/v1/admin/policy-releases {versionIds}` activates the set atomically (`deploy/scripts/upload-release.sh` does all of it) → watch `GET /api/v1/admin/policy-releases/current` until `match` and `ready` → `POST /api/v1/admin/policy-bundles/rollback?bundle=<name>` restores one bundle if needed. Readiness (`/actuator/health/readiness`, component `policyRelease`) is DOWN while OPA runs anything but the active release, so a replica with a stale bundle receives no traffic. Configuration changes: create a draft, activate, rollback.
The same steps are available in the admin console (`/policy-bundles` upload + activate with a live adoption watcher, `/policy-configurations` with a diff before activation). See `docs/admin-ui.md`.

## Team configuration and shadow mode
`PUT /api/v1/admin/teams/{teamId}` (or the console's Teams page) sets authorised prefixes, quotas and role overrides; it applies to the next evaluation. To soak a policy: create a policy configuration version with `enforcement: {"POLICY_ID": "shadow"}`, activate it, watch `GET /api/v1/admin/shadow-findings?policyId=POLICY_ID` and the metric, then create a version without the entry to enforce.

## Admin console
| Symptom | Cause | Action |
|---|---|---|
| Sign-in fails / loops | `OIDC_ISSUER` differs from the `iss` the provider mints, or the `/oidc/` proxy does not force the issuer Host | `docs/admin-ui.md` → Troubleshooting |
| Signed in, every call 401 | Governance validates another issuer than the token | compare `OAUTH2_ISSUER_URI` with the token in the account menu |
| "No governance role" | token lacks `governance-admin` / `governance-auditor` | identity provider role mapping, `ROLES_CLAIM` |
| Exception form falls back to free text | catalogue 503: OPA down or no active release | diagnostics banner on `/policy-bundles` |
| Every submission answers 503, readiness DOWN, `policy-releases/current` shows one bundle `match: false` | OPA refused that bundle revision (compile error, wrong root) or has not polled yet | wait one poll interval; then `rollback?bundle=<name>`; `verifyActivation` does it automatically after 90 s |

## Security notes
- OPA has no published port; its bundle endpoint on Governance is guarded by `OPA_BUNDLE_TOKEN` and must stay off the public network.
- Kafka credentials per service (`deploy/kafka/acls.sh`): only `governance` can write `requests.manifest`.
- Secrets via environment (`deploy/.env.example`); use TLS listeners (`SASL_SSL`) and a real issuer in production.
