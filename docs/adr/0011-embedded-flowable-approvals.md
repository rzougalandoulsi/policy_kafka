# ADR 0011: Human approvals on an embedded Flowable engine, same datasource, outbox unchanged

## Context
Approvals were a hand-written state machine: `ApprovalService` (quorum in Java, idempotent verdicts under a row
lock), `ApprovalReleaseService` (revalidation with OPA, digest and freshness checks, outbox commands) and
`ApprovalExpiryJob` (a 60 s TTL sweep). Correct and small, but with its own timer, no engine-level history and
every new workflow feature (escalation, multi-step approvals) to be written by hand. The request lifecycle
(`RequestLifecycle`, `RequestStatus`) is **not** a human workflow: it is the manifest reconciliation driven by
Orchestrator events, and the Orchestrator's desired-state reconciliation and the Processor's execution stay where
they are.

## Decision
- **Flowable 8.0.0, process engine only** (`flowable-spring-boot-starter-process`), embedded in the Governance JVM.
  8.0.0 is the first Flowable line built for Spring Boot 4 / Spring Framework 7 / Jackson 3; 7.2.0 does not start on
  Boot 4. IDM and the event registry are disabled (`flowable.idm.enabled=false`, `flowable.eventregistry.enabled=false`):
  approvers come from the JWT and `TeamAuthorization`, never from engine identity tables. No REST, CMMN, DMN or UI
  modules. Verified against Boot 4.1.2 by `ApprovalWorkflowIT` (real engine, real PostgreSQL).
- **Same datasource, same database, same schema.** The engine runs on the Governance `DataSource` and
  `DataSourceTransactionManager`, so a task completion, the `approval_decision` row, the `manifest_request`
  transition, the quota reservation and the `kafka_outbox` row commit or roll back together (what the concurrent
  approvals scenario relies on). Flowable owns the `ACT_*` tables and creates/upgrades them at startup
  (`flowable.database-schema-update=true`, locked on every start after the first); Flyway owns everything else and
  never touches `ACT_*`. `V7` adds only `approval_request.engine` and `process_instance_id` plus two idempotency
  indexes. Alternative rejected: a dedicated schema or datasource, which would split the transaction.
- **Governance tables stay the public record.** `approval_request` is the API shape and the correlation record
  (approval id = process business key, `process_instance_id` stored); Flowable task ids never appear in the API.
  `audit_record` remains the business audit trail; engine history (`history-level=audit`, cleaned after 365 days)
  is operational history only.
- **BPMN owns the human lifecycle, Java owns the effects.** `approval-decision.bpmn20.xml` (shared call activity:
  one verdict task at a time, quorum as a loop plus exclusive gateway), `manifest-approval.bpmn20.xml` (stage 1) and
  `action-plan-approval.bpmn20.xml` (stage 2) with an interrupting deadline timer and an `approvalCancelled` message
  boundary. Service tasks are Spring beans referenced by `flowable:delegateExpression` (no class names, no field
  injection, no approver identities) and are **synchronous**: they run inside `taskService.complete()` in the
  approver's transaction, so `POST /approvals/{id}/decisions` still answers with the released request status and an
  OPA failure rolls the verdict back (409), exactly as before. Only the deadline timers run on the async executor.
- **OPA stays the only policy decision point.** Gateways branch on the human outcome and on OPA's revalidation
  result (`ALLOW | DENY | STALE`); no Rego semantics in XML. Stored process context is identifiers only (request id,
  plan id and digest, decision id, policy revision, approval policy id), never a manifest or an action list.
- **Authorisation is never task assignment.** Every verdict is checked against the authenticated principal, the
  approver roles of the approval configuration and separation of duties under the approval's row lock before the
  task is completed (`ApprovalWorkflowService.decide`).
- **Kafka unchanged.** Two topics, same Avro envelopes, same `teamId` key, same outbox and relay; delegates only
  write outbox rows. Replayed triggers cannot open a second workflow (`uq_approval_request_open`, the lifecycle
  guards and the idempotent `open`).
- **Migration with a flag, nothing migrated silently.** `governance.workflow.engine = legacy | shadow | flowable`.
  Every row records the implementation that owns it (`engine`); legacy rows keep resolving through the retained
  legacy code (quorum in Java, `ApprovalExpiryJob` restricted to `engine = LEGACY`) until they drain
  (`governance_approvals_legacy_pending`). In `shadow` the legacy code stays authoritative and the process only
  records what it would have done (`WORKFLOW_SHADOW_OUTCOME`, `governance_workflow_shadow_outcomes_total`); exactly one
  implementation ever publishes. The legacy branch, the shadow mode and `ApprovalExpiryJob` are deleted once the
  drain gauge is zero everywhere (the cleanup checklist is in `docs/runbook.md`).

## Consequences
- Startup needs DDL rights on the governance database (already true for Flyway) and deploys the three definitions;
  a new definition version is created only when the XML changed, running instances finish on their version. A BPMN
  change may only reference delegate beans present in both images of a rolling update; beans are removed one
  release after their last BPMN reference. Rolling back an image redeploys the previous XML as a new version.
- Timer-driven paths run on the engine's executor threads with their own transaction; a deadline whose effects fail
  repeatedly becomes a dead-letter job (`governance_workflow_jobs_deadletter`, runbook recovery) instead of a lost
  expiry. Pods pause their executor gracefully on shutdown (20 s, below the 25 s shutdown phase) and, in the
  `openshift` profile, hand acquired jobs back (`unlock-owned-jobs`).
- The release still holds a database transaction across the OPA call (30 s timeout), as it did before.
- Memory: the engine adds MyBatis caches and a small job executor; pod requests are raised to 1Gi (limit 2Gi).
