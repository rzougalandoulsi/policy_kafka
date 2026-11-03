# Orchestrator integration requirements

The Orchestrator is **not implemented in this repository**. Its source (the `ssengine` prototype) was analysed
(`repository-analysis.md`) and the Governance side was built against the documented Avro contract
(`kafka-contract.md`). A private local stand-in (`orchestrator-simulator/`, kept out of this
repository) behaves exactly as described below so the platform can be exercised end to end; it is not production code.

## What the real Orchestrator must add

1. **Consume `requests.manifest`** (`ManifestCommand`, key `teamId`) instead of ingesting `<team>.repo.manifest` directly.
   Direct ingestion is the governance bypass and must be disabled (or converted into SUBMIT commands that still
   require a Governance decision; Governance rejects plans for unknown requests).
2. **SUBMIT_MANIFEST** → technical validation, hydrate confirmed state, diff, build an **immutable plan**
   (`planId`, `actions[PlannedAction]`, `planDigest = Digests.planDigest(actions, manifestDigest, stateVersion)`),
   persist it keyed by `requestId`, publish `PLAN_PROPOSED` (`response.manifest`, key `teamId`) with `stateVersion`.
   A duplicate `SUBMIT_MANIFEST` for a known `requestId` re-publishes the same plan; it never re-plans.
3. **AUTHORIZE_PLAN** → release the plan to the Processor **only if all of**: a pending plan exists for `requestId`,
   `planId` and `planDigest` match, `decisionId` is present, `authorizationExpiresAt` is in the future, and the plan's
   `stateVersion` equals the current confirmed `stateVersion`. Mark the plan released **before** forwarding so a
   replayed command is a no-op. Otherwise publish `MANIFEST_REJECTED` with reason
   `UNKNOWN_PLAN | DIGEST_MISMATCH | MISSING_DECISION | AUTHORIZATION_EXPIRED | STALE_PLAN`.
   An empty plan completes immediately with `MANIFEST_COMPLETED`.
4. **REJECT_PLAN** → drop the pending plan; a later authorization is `UNKNOWN_PLAN`.
5. **Release** = existing `Action` records to `teams.changes` (header `intendedFor=processor`) plus the `EndOfBatch`
   marker carrying `correlationId = planId`, `requestId`, `planId`, `teamId`, `repoId`, `environment`.
6. **Reconcile** Processor statuses per `correlationId` (de-duplicate per resource so an at-least-once Processor cannot
   complete a batch early), apply successes (creates add, deletes/revokes remove; the stored plan tells which),
   **increment `RepoConfirmed.stateVersion`**, then publish `MANIFEST_COMPLETED` (all succeeded) or
   `MANIFEST_FAILED(PARTIAL_FAILURE)` with per-action results and the new `stateVersion`.
7. **Scope confirmed state per environment** (`teamId|repoId|environment`). Manifests now carry `environment`; a
   production manifest must not be diffed against development state. Legacy records without environment keep the
   `teamId|repoId` key.

## Schema changes already provided in `contracts/` (all additive, defaulted, backward compatible)
- `RepoDesired`: `environment`, `topics[TopicSpec]`, `identityPools[PoolSpec]`, `rbacs[RbacSpec]` (the prototype's differ already required these).
- `RepoConfirmed`: `stateVersion`, `environment`.
- `EndOfBatch`: `teamId`, `repoId`, `requestId`, `planId`, `environment`.
- `BatchState`: `requestId`, `planId`, `environment`.
- `Action`, `ProcStatus` unchanged ⇒ the Processor is untouched.

## Kafka Streams guidance (if the handshake is implemented in the existing topology)
- Re-key commands to `teamId|repoId|environment` through one explicit repartition and process them in the subtopology
  that owns the confirmed-state store and a `pending-plans` store, so SUBMIT and AUTHORIZE for a request land on the
  same task. Using `teamId` as the key does **not** by itself make stores accessible across subtopologies.
- `requests.manifest` and `teams.repo.manifest` must have the same partition count (assert at startup).
- Use `RecordNameStrategy` and configure the serdes with the Schema Registry URL; `teams.changes` carries two record types.
- Exactly-once v2 keeps "mark released" and "forward actions" atomic.

## Prototype defects to fix on the way
`RepoDesired` lacked the fields `ManifestDiffer` used (did not compile); `SpecificAvroSerde` never received the registry
URL; END_OF_BATCH was read with the `Action` serde; the diff ran twice per message; `BatchState.teamId/repoId` were
never populated; the `<team>.status` feedback stream was never written.

## ACLs for the real Orchestrator principal
Read `requests.manifest`, `request.status`, `teams`; write `response.manifest`, `teams.changes`, `teams.repo.manifest`
(read+write), `<team>.status`; all operations on its Streams-internal topics, group and transactional ids (prefixed with
the application id); `Describe` on the cluster. `deploy/kafka/acls.sh` grants the stand-in a smaller subset.
