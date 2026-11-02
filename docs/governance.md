# Governance service

## Request lifecycle
```
RECEIVED → STAGE1_DENIED | PENDING_APPROVAL_STAGE1 | SUBMITTED | EVALUATION_ERROR
SUBMITTED → PLAN_PROPOSED → PLAN_AUTHORIZED | PLAN_REJECTED | PENDING_APPROVAL_STAGE2 | EVALUATION_ERROR
PENDING_APPROVAL_* → PLAN_AUTHORIZED | PLAN_REJECTED | STAGE1_DENIED | SUBMITTED | APPROVAL_EXPIRED | STALE_PLAN
PLAN_AUTHORIZED → COMPLETED | FAILED | STALE_PLAN | REJECTED_BY_ORCHESTRATOR
```
`RequestLifecycle` is the single source of legal transitions; `manifest_request.version` provides optimistic locking; every transition is appended to `request_status_history`.

For an animated walkthrough of these transitions, open [`flow-animation.html`](flow-animation.html) from a checkout (a single self-contained page; GitHub shows only its source). It offers a sequence diagram and a component map of the running services, database tables and Kafka topics; both trace the same scenarios. Keep its scenario data in step with `RequestLifecycle`, `GovernanceTypes.avsc`, `decision.rego` and `catalog.rego`.

## REST API (OpenAPI at `/v3/api-docs`, UI at `/swagger-ui.html`)
| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/manifests` | team member with environment access | `Idempotency-Key` header; 202 accepted/pending approval, 200 replay, 422 denied (violations), 409 key reuse with different manifest, 503 fail-closed |
| `GET /api/v1/requests/{id}` `/decisions` `/plan` | requester, team member, approver, auditor, admin | |
| `GET /api/v1/approvals` `/{id}`; `POST /{id}/decisions` | approver role (list also auditors; team members with `?teamId=`) | idempotent per approver, separation of duties, quorum |
| `/api/v1/admin/policy-configurations` | admin (read: auditor) | draft → activate → rollback, audited |
| `/api/v1/admin/policy-bundles` | admin (read: auditor) | upload one built bundle (root `governance/<name>`) + test report, activate, `rollback?bundle=`, `diagnostics` |
| `/api/v1/admin/policy-releases` | admin (read: auditor) | `current`: expected versus loaded revision per bundle; `POST`: activate one version per bundle atomically |
| `/api/v1/admin/exceptions` | admin (read: auditor) | create / revoke scoped exceptions |
| `/api/v1/admin/teams`, `/{teamId}`, `/{teamId}/history`, `/{teamId}/usage` | admin (read: auditor) | team configuration (authorised prefixes, quotas, allowed roles, environment parameters; `source` governance / external / default), its versions, confirmed usage and open reservations (ADR 0009, 0010) |
| `GET /api/v1/admin/shadow-findings` | auditor | violations and approval requirements of policies in shadow mode (`?policyId=&teamId=&environment=&since=`) |
| `GET /api/v1/admin/policies` | auditor | merged policy catalogue of the loaded bundles (`data.governance.core.policy_catalog`): id, bundle, kind, severity, bypassable, section; 503 when OPA is unreachable |
| `/api/v1/admin/approval-configurations` | admin | approver roles, quorum, SoD, TTL |
| `/api/v1/admin/audit`, `/api/v1/admin/dead-letters` | auditor / admin | |
| `GET /internal/opa/bundles/governance` | static bearer token (OPA only) | ETag / 304 |

Identity comes only from the validated JWT: `sub`, `teams[]`, `environments[]`, `roles[]` (claim names configurable). Nothing privileged is read from request bodies.

## Decision model
`GovernanceDecision{id, requestId, planId?, stage ∈ REQUEST|ACTION_PLAN, type ∈ ALLOW|DENY|REQUIRE_APPROVAL|ERROR, policyRevision, policyRevisions, configurationVersion, digest, matchedPolicies, violations, approvalRequirements, appliedExceptions, evaluatedAt}`.
Aggregation (`DecisionAggregator`, mirrored in `bundles/core/core.rego`): HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW. Hard denies can neither be exempted nor approved around; only `bypassable` violations honour exceptions, and only exceptions Governance itself resolved. Every violation and approval requirement carries `enforcement` (`enforce` or `shadow`, from `configuration.enforcement[policyId]`): shadow findings are kept in the decision, written to `shadow_finding` and counted in `governance_shadow_findings_total`, but never block and never open an approval. The Java aggregate must agree with OPA's own `decision`, otherwise the decision is `ERROR` (fail closed).

## OPA integration
`POST {opa.url}/v1/data/governance/core/decision?provenance=true` with **input contract 2.0** (`contracts/opa/input.schema.json`, validated by the unit test `OpaInputContractTest`): `{contractVersion, stage, now, principal, team{id, authorizedPrefixes, quotas, allowedRoles, environments, configurationVersion, source}, repository{id, ownedByTeam, stateVersion}, environment, manifest, actions, plan, usage{current, reserved, delta, byRepository, asOfStateVersions, snapshotAge}, ownership{resources}, request, configuration (+ enforcement), exceptions}`. Everything privileged comes from Governance's own sources: the JWT, `team_configuration`, `repository_usage` (consumed from `teams.repo.manifest`), `quota_reservation`, `policy_exception`. Timeouts, bounded retries on transient errors, release verification (the provenance revision of **every** bundle must equal the ACTIVE release, the composition must report `ready`, and core's `contractVersion` major must be one of `governance.opa.supported-contract-majors`, 1 and 2 during the transition), health indicator (`/actuator/health` → `opa`) and a readiness indicator (`policyRelease`, part of `/actuator/health/readiness`) that is DOWN until OPA runs exactly the active release. An unreachable OPA, a missing bundle, a mismatched revision or an incompatible contract version yields `ERROR`: nothing is published, the request stays retryable (`EVALUATION_ERROR`), and the API answers 503.

### Team configuration, usage and quotas (ADR 0009, 0010)
- `team_configuration` is the system of record (`source=governance`); rows marked `external` are read-only until an admin takes ownership back; teams without a row use defaults (one prefix `<teamId><separator>`, no quotas). Changes are versioned, kept in `team_configuration_history`, audited, and apply to the next evaluation.
- `RepositoryStateConsumer` reads the Orchestrator's confirmed state from `teams.repo.manifest` (read-only; group `governance-usage`) into `repository_usage`. `UsageProjector` turns it into `input.usage` (confirmed usage of all repositories of the team in the environment, reservations of other in-flight plans, the delta of this request) and `input.ownership` (topics and pools another repository already owns).
- **Freshness guard**: a stage 2 evaluation whose repository snapshot is older than `plan.stateVersion` returns `ERROR` and the inbox retries, so quotas are never computed on stale usage (`governance.usage.require-fresh-snapshot`).
- **Admission**: stage 2 holds `SELECT ... FOR UPDATE` on the team's configuration row across the evaluation and inserts a `quota_reservation` with the plan's deltas in the same transaction as `PLAN_AUTHORIZED`; two concurrent approvals of one team admit exactly one plan. Reservations are released on terminal events, approval expiry and by TTL (`authorizationValidity`).

## Dynamic policy management
- **Logic**: six Rego v1 bundles under `policies/bundles/` with non-overlapping roots `governance/<name>` (ADR 0007): `core` (composition, global prohibitions, exception matching), `manifests`, `naming`, `quotas`, `resources`, `changes`. Each domain bundle exposes `violations`, `approvals`, `catalog` and `contract_version`; `core` references them dynamically and composes one decision, with `ready == false` (hence fail closed) when a bundle is missing or its contract major differs. `policies/build.sh` runs opa check/test/build per bundle via Docker and writes `dist/release.json`, the tested set of revisions. `policies/` is laid out as the future `governance-policies` repository (contracts, fixtures, `scripts/validate.sh`, Workflows A and B that build signed, immutable Artifactory artifacts and candidate release manifests; see `policies/README.md`); Governance keeps its pinned copy of the input/output schemas under `contracts/opa/`. Bundles are uploaded one by one with the shared test report (`POST /api/v1/admin/policy-bundles`, root checked against the bundle name), and activated together as a release (`POST /api/v1/admin/policy-releases`, atomic, all six required) or individually; OPA polls `/internal/opa/bundles/{name}`; `GET /api/v1/admin/policy-releases/current` shows expected versus loaded revision per bundle; `PolicyBundleService.verifyActivation` restores the previous version of a bundle OPA does not adopt. Rollback is per bundle (`POST /api/v1/admin/policy-bundles/rollback?bundle=<name>`). No endpoint accepts raw Rego. All of this is **local mode** (`governance.opa.mode=local`). In **release mode** (`openshift` profile, ADR 0008) the expected release is read from the mounted release manifest (`governance.opa.release-file`, `FilePolicyReleaseSource`, re-read when the file changes), OPA pulls the pinned signed revisions from Artifactory as a sidecar, the upload/activate/rollback endpoints answer 409 `POLICY_SOURCE_IS_GIT`, `/internal/opa/bundles/*` is 404 and the automatic rollback job is inactive; `GET /api/v1/admin/policy-releases/current` reports `mode` and `source`. A build started with the `openshift` profile refuses to run in local mode.
- **Configuration**: `policy_configuration` versions (naming patterns, roles, limits, destructive switches, approval thresholds, and the optional `enforcement` map `policyId -> enforce|shadow`) passed to OPA as `input.configuration`; every decision records the release id (`policyRevision`), the revision per bundle (`policyRevisions`) and `configurationVersion`.
- **Shadow mode**: a policy set to `shadow` is evaluated and its findings are recorded (`shadow_finding`, `GET /api/v1/admin/shadow-findings`, metric `governance_shadow_findings_total{policyId,kind}`) but it never denies and never opens an approval. This is how new or migrated rules are soaked before they enforce (plan section 4.3).

## Scoped exceptions
`{policyId, principalId?, teamId, environment|*, resourcePattern (glob), actionTypes[], expiresAt, reason}`; revocable, audited, admin-only. Governance resolves the valid candidates per (principal, team, environment) and OPA matches resource and action scope. Hard-deny policies ignore exceptions.

## Approvals
Opened by REQUIRE_APPROVAL at either stage, bound to the manifest digest (stage 1) or the plan digest (stage 2). Configurable approver roles, quorum, separation of duties and TTL. On quorum the request is **revalidated** (policies + exceptions re-evaluated, plan digest checked, plan freshness checked against the latest confirmed `stateVersion`) before SUBMIT_MANIFEST / AUTHORIZE_PLAN is enqueued. Expired approvals are closed by `ApprovalExpiryJob`, which also tells the Orchestrator to drop the plan. No Kafka thread ever waits for a human.

## Persistence
PostgreSQL + Flyway (`V1`–`V6`): requests, history, decisions, proposed plans, repository state versions, policy configurations, bundle versions, exceptions, approval configuration/requests/decisions, audit, Kafka inbox/outbox/dead letters, team configuration (+ history), repository usage, quota reservations, shadow findings. All JDBC work runs on a dedicated bounded executor (`Blocking`), never on Reactor event-loop threads.
