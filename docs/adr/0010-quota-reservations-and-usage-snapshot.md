# ADR 0010: Projected team usage from the confirmed state, with quota reservations under a per-team row lock

## Context
Team quotas must hold across every repository of a team and across plans that are authorised but not executed yet.
Governance never computes diffs and does not own the cluster state; the Orchestrator publishes the confirmed state per
repository on `teams.repo.manifest` (`RepoConfirmed`, compacted, keyed `teamId|repoId|environment`, see ADR 0005).

## Decision
- **Usage snapshot.** `RepositoryStateConsumer` (group `governance-usage`, read-only ACL) upserts `repository_usage`
  monotonically by `stateVersion`. `RepoConfirmed` carries names only, so the partitions of confirmed topics come from
  the last manifest Governance saw complete for that repository (`declared_partitions`), defaulting to 1.
- **Projection in Java, rule in Rego.** `UsageProjector` computes `input.usage.current` (all repositories of the team in
  the environment), `reserved` (open reservations of other requests), `delta` (stage REQUEST: declared manifest minus
  this repository's confirmed usage; stage ACTION_PLAN: the planned actions), `byRepository`, `asOfStateVersions`,
  `snapshotAge`, plus `input.ownership.resources` (topics and pools this request names that another repository's
  confirmed state owns) and `input.repository.ownedByTeam`. The quotas bundle compares `current + reserved + delta` to
  `input.team.quotas` (`TEAM_QUOTA_EXCEEDED`, bypassable); the manifests bundle reports ownership conflicts.
- **Freshness guard.** At stage 2, when the snapshot of the request's repository is older than `plan.stateVersion`, the
  decision is `ERROR` and the inbox retries (`governance.usage.require-fresh-snapshot`, default true). Quotas are never
  computed on usage the Orchestrator has already moved past.
- **Admission control.** `ManifestEventHandler` and `ApprovalReleaseService` take `SELECT ... FOR UPDATE` on the team's
  `team_configuration` row *before* evaluating stage 2 and insert a `quota_reservation` with the plan's deltas in the
  same transaction as `PLAN_AUTHORIZED` and the outbox row. Two simultaneously approved plans of one team are therefore
  serialised: the second evaluation already sees the first plan's committed reservation and is denied by Rego. The
  arithmetic is not duplicated in Java; holding the lock across the OPA call is what makes the Rego check sufficient.
  Teams without a stored configuration have no quotas and take no lock.
- **Release.** Reservations are released on `MANIFEST_COMPLETED|FAILED|REJECTED` (including `STALE_PLAN`), on approval
  expiry, and by TTL (the authorisation validity, swept every 30 s). Open reservations are visible in
  `GET /api/v1/admin/teams/{id}/usage` and the gauge `governance_quota_reservations_open`.

## Consequences
- Governance needs read access to `teams.repo.manifest`; the real Orchestrator must publish `RepoConfirmed` there with
  partitions if `topicPartitions` quotas are to be exact (plan assumption 3). Until then partitions are "as last declared".
- A long OPA outage during stage 2 holds the team's row lock only for the bounded evaluation timeout per attempt.

## Update 2026-11-02 (Phase 8 review)
Implemented as decided and proven by the end-to-end scenarios (joint quota across two repositories, concurrent approvals admitting exactly one plan, prefix change without restart, shadow findings never blocking). Still open and owned by Phase 6: whether the real Orchestrator's `RepoConfirmed` carries partitions and the other resource types; until then `topicPartitions` usage is "as last declared" (`repository_usage.declared_partitions`, default 1). The freshness guard and the reservation TTL are the knobs operators have (`governance.usage.require-fresh-snapshot`, `authorizationValidity`).
