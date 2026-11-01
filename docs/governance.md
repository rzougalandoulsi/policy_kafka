# Governance service

## Request lifecycle
```
RECEIVED → STAGE1_DENIED | PENDING_APPROVAL_STAGE1 | SUBMITTED | EVALUATION_ERROR
SUBMITTED → PLAN_PROPOSED → PLAN_AUTHORIZED | PLAN_REJECTED | PENDING_APPROVAL_STAGE2 | EVALUATION_ERROR
PENDING_APPROVAL_* → PLAN_AUTHORIZED | PLAN_REJECTED | STAGE1_DENIED | SUBMITTED | APPROVAL_EXPIRED | STALE_PLAN
PLAN_AUTHORIZED → COMPLETED | FAILED | STALE_PLAN | REJECTED_BY_ORCHESTRATOR
```
`RequestLifecycle` is the single source of legal transitions; `manifest_request.version` provides optimistic locking; every transition is appended to `request_status_history`.

For an animated walkthrough of these transitions across the client, Governance, OPA, Kafka and the Orchestrator, open [`flow-animation.html`](flow-animation.html) from a checkout (it is a single self-contained page; GitHub shows only its source). Keep its scenario data in step with `RequestLifecycle`, `GovernanceTypes.avsc`, `decision.rego` and `catalog.rego`.

## REST API (OpenAPI at `/v3/api-docs`, UI at `/swagger-ui.html`)
| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/manifests` | team member with environment access | `Idempotency-Key` header; 202 accepted/pending approval, 200 replay, 422 denied (violations), 409 key reuse with different manifest, 503 fail-closed |
| `GET /api/v1/requests/{id}` `/decisions` `/plan` | requester, team member, approver, auditor, admin | |
| `GET /api/v1/approvals` `/{id}`; `POST /{id}/decisions` | approver role (list also auditors; team members with `?teamId=`) | idempotent per approver, separation of duties, quorum |
| `/api/v1/admin/policy-configurations` | admin (read: auditor) | draft → activate → rollback, audited |
| `/api/v1/admin/policy-bundles` | admin (read: auditor) | upload built bundle + test report, activate, rollback, `diagnostics` |
| `/api/v1/admin/exceptions` | admin (read: auditor) | create / revoke scoped exceptions |
| `GET /api/v1/admin/policies` | auditor | policy catalogue of the active bundle (`catalog.rego`): id, kind, severity, bypassable, section; 503 when OPA is unreachable |
| `/api/v1/admin/approval-configurations` | admin | approver roles, quorum, SoD, TTL |
| `/api/v1/admin/audit`, `/api/v1/admin/dead-letters` | auditor / admin | |
| `GET /internal/opa/bundles/governance` | static bearer token (OPA only) | ETag / 304 |

Identity comes only from the validated JWT: `sub`, `teams[]`, `environments[]`, `roles[]` (claim names configurable). Nothing privileged is read from request bodies.

## Decision model
`GovernanceDecision{id, requestId, planId?, stage ∈ REQUEST|ACTION_PLAN, type ∈ ALLOW|DENY|REQUIRE_APPROVAL|ERROR, policyRevision, configurationVersion, digest, matchedPolicies, violations, approvalRequirements, appliedExceptions, evaluatedAt}`.
Aggregation (`DecisionAggregator`, mirrored in `decision.rego`): HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW. Hard denies can neither be exempted nor approved around; only `bypassable` violations honour exceptions, and only exceptions Governance itself resolved. The Java aggregate must agree with OPA's own `decision`, otherwise the decision is `ERROR` (fail closed).

## OPA integration
`POST {opa.url}/v1/data/governance/decision?provenance=true` with input `{stage, principal, team, environment, manifest, actions, plan, request, configuration, exceptions}`. Timeouts, bounded retries on transient errors, revision verification (the `provenance.bundles.governance.revision` must equal the ACTIVE bundle revision), health indicator (`/actuator/health` → `opa`). An unreachable OPA or a missing bundle yields `ERROR`: nothing is published, the request stays retryable (`EVALUATION_ERROR`), and the API answers 503.

## Dynamic policy management
- **Logic**: Rego v1 in `policies/governance`, built and tested by `policies/build.sh` (opa check/test/build via Docker), uploaded as a versioned bundle with its test report. Activation flips the ACTIVE row; OPA polls `/internal/opa/bundles/governance`; `PolicyBundleService.verifyActivation` checks OPA's revision and automatically restores the previous version if OPA does not adopt the new one. No endpoint accepts raw Rego.
- **Configuration**: `policy_configuration` versions (naming patterns, roles, quotas, destructive switches, approval thresholds) passed to OPA as `input.configuration`; every decision records `(policyRevision, configurationVersion)`.

## Scoped exceptions
`{policyId, principalId?, teamId, environment|*, resourcePattern (glob), actionTypes[], expiresAt, reason}`; revocable, audited, admin-only. Governance resolves the valid candidates per (principal, team, environment) and OPA matches resource and action scope. Hard-deny policies ignore exceptions.

## Approvals
Opened by REQUIRE_APPROVAL at either stage, bound to the manifest digest (stage 1) or the plan digest (stage 2). Configurable approver roles, quorum, separation of duties and TTL. On quorum the request is **revalidated** (policies + exceptions re-evaluated, plan digest checked, plan freshness checked against the latest confirmed `stateVersion`) before SUBMIT_MANIFEST / AUTHORIZE_PLAN is enqueued. Expired approvals are closed by `ApprovalExpiryJob`, which also tells the Orchestrator to drop the plan. No Kafka thread ever waits for a human.

## Persistence
PostgreSQL + Flyway (`V1`–`V4`): requests, history, decisions, proposed plans, repository state versions, policy configurations, bundle versions, exceptions, approval configuration/requests/decisions, audit, Kafka inbox/outbox/dead letters. All JDBC work runs on a dedicated bounded executor (`Blocking`), never on Reactor event-loop threads.
