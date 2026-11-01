# Kafka contract: Governance ↔ Orchestrator

Exactly two topics. Both keyed by **`teamId`** (string), values Avro `SpecificRecord`, Schema Registry subject naming **RecordNameStrategy** (`com.itland.platform.avro.ManifestCommand`, `com.itland.platform.avro.ManifestEvent`).

| Topic | Producer | Consumer | Record |
|---|---|---|---|
| `requests.manifest` | Governance | Orchestrator | `ManifestCommand` |
| `response.manifest` | Orchestrator | Governance | `ManifestEvent` |

## ManifestCommand (`contracts/src/main/avro/ManifestCommand.avsc`)

`commandId, commandType ∈ {SUBMIT_MANIFEST, AUTHORIZE_PLAN, REJECT_PLAN}, requestId, teamId, repositoryId, environment, planId?, planDigest?, decisionId?, occurredAt, payload`

| commandType | payload | semantics |
|---|---|---|
| SUBMIT_MANIFEST | `SubmitManifestPayload{manifest: RepoDesired, manifestDigest}` | Stage 1 passed; plan it. Duplicate requestId ⇒ the same plan is re-proposed. |
| AUTHORIZE_PLAN | `AuthorizePlanPayload{stateVersion, authorizationExpiresAt}` + `planId, planDigest, decisionId` | Stage 2 passed. Orchestrator releases only if planId+digest match the stored plan, decisionId is present, authorization not expired and `stateVersion` equals the current confirmed state version. Duplicates are no-ops. |
| REJECT_PLAN | `RejectPlanPayload{reasonCode, violations[]}` | Drop the pending plan. |

## ManifestEvent (`ManifestEvent.avsc`)

`eventId, eventType ∈ {PLAN_PROPOSED, MANIFEST_COMPLETED, MANIFEST_FAILED, MANIFEST_REJECTED}, requestId, teamId, repositoryId, environment, planId?, planDigest?, decisionId?, stateVersion?, occurredAt, payload`

| eventType | payload |
|---|---|
| PLAN_PROPOSED | `PlanProposedPayload{actions[PlannedAction], manifestDigest}` (+ `planId`, `planDigest`, `stateVersion`) |
| MANIFEST_COMPLETED | `ManifestCompletedPayload{results[ActionResult]}` (+ new `stateVersion`) |
| MANIFEST_FAILED | `ManifestFailedPayload{reasonCode, message, results[]}` |
| MANIFEST_REJECTED | `ManifestRejectedPayload{reasonCode ∈ UNKNOWN_PLAN, DIGEST_MISMATCH, MISSING_DECISION, AUTHORIZATION_EXPIRED, STALE_PLAN, INVALID_COMMAND, message}` |

Headers on every record: `requestId`, `planId` (when known), `commandType` / `eventType`.

## Digests and versions

- `manifestDigest` = SHA-256 of a canonical, order-independent rendering of `RepoDesired` (`Digests.manifestDigest`).
- `planDigest` = SHA-256 over the sorted action list + `manifestDigest` + `stateVersion` (`Digests.planDigest`). Governance recomputes it on PLAN_PROPOSED and rejects mismatches.
- `stateVersion` = `RepoConfirmed.stateVersion`, incremented by the Orchestrator on every reconcile. Governance records the latest version it saw per (team, repository, environment) and refuses to release an approved plan computed against an older version (`STALE_PLAN`).

## Routing and correlation on the Orchestrator side

Authorization responses must reach the owner of the pending plan. Required design (see `orchestrator-integration.md`): re-key commands to `teamId|repoId|environment` through one explicit repartition and process SUBMIT and AUTHORIZE for a request on the same task, next to the pending-plan and confirmed-state stores. Co-location must rely on store connectivity, not on the `teamId` key itself, and `requests.manifest` and `teams.repo.manifest` need equal partition counts. The stand-in keeps the same state in memory.

## Delivery semantics

- Governance publishes through a **transactional outbox** (same DB transaction as the state change) with an idempotent producer; consumes with manual commits and an **inbox** keyed by `eventId` (duplicates ignored, out-of-order events recorded as `IGNORED`, transient failures retried with backoff, exhausted ones dead-lettered).
- The Orchestrator must treat a duplicate `AUTHORIZE_PLAN` as a no-op (plan already released); the stand-in does.
- Existing topics (`teams.changes`, `request.status`, `teams.repo.manifest`) keep their contracts; only additive, defaulted fields were added (`EndOfBatch.requestId/planId/teamId/repoId/environment`, `RepoConfirmed.stateVersion/environment`, `RepoDesired.environment/topics/identityPools/rbacs`).

## Confirmed state read by Governance

`teams.repo.manifest` (compacted, `RepoConfirmed`, keyed `teamId|repoId|environment` per ADR 0005) is written by the Orchestrator after every reconcile and **read, never written,** by Governance (consumer group `governance-usage`). It feeds `repository_usage`, the source of projected team usage (`input.usage`) and cross-repository ownership checks (`input.ownership`) in input contract 2.0 (ADR 0010). `RepoConfirmed` carries topic names only; Governance derives partitions from the last manifest it saw complete for the repository. The stand-in publishes the record just before the terminal event, as the real Orchestrator does.

## ACLs (deploy/kafka/acls.sh)

Only `User:governance` may write `requests.manifest`; only `User:orchestrator` may write `response.manifest` and `teams.repo.manifest`; Governance may read `teams.repo.manifest` (groups `governance`, `governance-usage`); the Processor principal can read `teams.changes` and write `request.status` only.
