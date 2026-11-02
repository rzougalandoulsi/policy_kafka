# Plan: Embed Flowable BPMN in Governance and replace the custom approval workflow

**Source**: conversational `/ecc:plan` request (2026-11-01)
**Complexity**: Large (7 phases; estimates per phase in section 16)
**Status**: IMPLEMENTED (2026-11-02) with the recommended decisions D1 to D6. Phases 2 to 6 are in the tree; Phase 7 (deleting the legacy branch) waits for the drain gate (`governance_approvals_legacy_pending == 0` in every environment) and is written up as a checklist in `docs/runbook.md`, "Approval workflow operations".

Implementation notes (deviations from the design below, all recorded in ADR 0011 and the runbook):
- The schema update lock (`setUseLockForDatabaseSchemaUpdate`) needs `ACT_GE_PROPERTY` to exist, so it is enabled only on starts after the first (`FlowableConfig.engineSchemaPresent`); the first creation runs unlocked.
- The `ReleaseGateway` seam became simpler: delegates check the `authoritative` process variable and call `ShadowOutcomeRecorder` in shadow mode; the two BPMN files carry one `shadowOnly` default flow each, removed in Phase 7.
- `revalidationResult` and `approvalOutcome` drive one `rejectApprovalDelegate` (verdict REJECT, DENY on revalidation, STALE) instead of per-reason delegates; the plan's `MarkRequestDelegate`/`ApprovalProjection` are `ExpireApprovalDelegate`, `CancelApprovalDelegate` and `ApprovalReleaseSteps.resolveApproval`.
- No `approval-ttl-override` test property: tests drive deadlines through the engine (`ManagementService` with the executor paused, or the engine clock for the live executor path).
- The Flowable executor runs on a dedicated `@Process` `ThreadPoolTaskExecutor` (`gov-workflow-*`), because Boot's `applicationTaskExecutor` backs off when another `Executor` bean exists (Governance's `blockingExecutor`).
- Engine tests live in `ApprovalWorkflowIT` (deployment, flows, quorum, SoD, idempotent open, concurrency, deadline via live executor and via a second engine, cancellation, definition upgrade, API shape) and `ApprovalWorkflowShadowIT`; the end-to-end suite gained scenarios 23 (stage-2 expiry reaches the Orchestrator stand-in) and 24 (replayed PLAN_PROPOSED opens no second approval) and runs under all three engine modes.

Markers: `[F]` confirmed repository fact (file read in this session), `[V]` verified externally (Maven Central POM or Flowable release notes, 2026-11-01), `[D]` design decision proposed here, `[A]` assumption to confirm with the owners.

## 0. Executive summary

Governance already has a complete, durable, audited approval implementation: `approval_request` / `approval_decision` tables, `ApprovalService` (decide, quorum, idempotent verdicts), `ApprovalReleaseService` (revalidation with OPA, digest and freshness checks, outbox commands), `ApprovalExpiryJob` (TTL sweep). It is small (about 450 lines) and correct, but it is a hand-written state machine with its own timer and no engine-level history. The migration embeds **Flowable 8.0.0** (`flowable-spring-boot-starter-process`, the only Flowable line built for Spring Boot 4 / Spring Framework 7 / Jackson 3) inside the Governance JVM, models the two approval flows as BPMN with a shared "collect verdicts" sub-process, keeps Governance's domain tables as the public projection and audit source, and keeps the transactional outbox as the only Kafka path. OPA remains the only policy decision point; BPMN gateways only branch on OPA's *result* (`ALLOW | DENY | ERROR`) and on the human verdict.

What changes for callers: nothing in the REST contract, nothing on `requests.manifest` / `response.manifest`. What changes inside: human-task lifecycle, deadlines, quorum loop, cancellation and workflow history move into Flowable; revalidation and publishing become BPMN service tasks executed **synchronously in the approver's transaction**, so `POST /approvals/{id}/decisions` still answers with the released `requestStatus` and still rolls back the verdict when OPA is unavailable (today's behaviour, asserted by the e2e test).

What is explicitly **not** touched: the request lifecycle `RequestLifecycle` / `RequestStatus` (that is the manifest reconciliation state driven by Orchestrator events, not a human workflow), the inbox/outbox/dead-letter machinery, the Orchestrator and Processor stand-ins, the Rego bundles.

## 1. Existing workflow implementation inventory [F]

### 1.1 Modules and runtime shape
- Maven reactor `pom.xml`: parent `spring-boot-starter-parent:4.1.2` (Spring Framework 7.0.10, Flyway 12.4.0, HikariCP 7.0.2, PostgreSQL driver 42.7.14 managed), Java 21, modules `contracts`, `orchestrator-simulator`, `processor-simulator`, `governance`. Confluent clients `8.0.0-ce`, Avro 1.11.4.
- `governance/` is a **WebFlux** application (`spring-boot-starter-webflux`, `spring-boot-starter-webclient`), not MVC. All JDBC runs on a dedicated bounded pool through `configuration/Blocking.java` + `BlockingExecutionConfig.java` (`TransactionTemplate` over Boot's `DataSourceTransactionManager`). ADR 0003 records this. Jackson 3 (`tools.jackson`) is already in use (`ApprovalRepository` imports `tools.jackson.core.type.TypeReference`).
- The real Orchestrator and Processor are **not in this repository** (README table: "existing, not implemented here"); `orchestrator-simulator` and `processor-simulator` are stand-ins used by the end-to-end test. The request text describes them as existing services; this plan treats their behaviour as the contract in `docs/kafka-contract.md` and `docs/orchestrator-integration.md` and changes nothing on their side.

### 1.2 Approval and workflow classes (what the migration is about)
| File | Role today | Classification |
|---|---|---|
| `governance/src/main/java/com/itland/governance/application/approvals/ApprovalService.java` | `open()` creates the PENDING row from the ACTIVE `approval_configuration`; `decide()` locks the row, checks eligibility, records the verdict (idempotent per approver), computes quorum, resolves and calls release/reject **inside the same transaction**; `cancelPending()` on terminal events; `list()`/`get()` with team authorisation | **Adapt**: keep authorisation, eligibility, idempotent verdict recording, read side; replace quorum/resolution/orchestration by Flowable task completion |
| `.../application/approvals/ApprovalReleaseService.java` | Stage 1: digest check, OPA re-evaluation, `SUBMITTED` + `enqueueSubmit` or `STAGE1_DENIED`. Stage 2: plan digest/plan id check, freshness vs `latestStateVersion` (STALE_PLAN + REJECT_PLAN), `lockTeam`, OPA re-evaluation, `PLAN_AUTHORIZED` + reserve + `enqueueAuthorize`, or `PLAN_REJECTED` + REJECT_PLAN; `rejectAfterApproval` | **Adapt**: logic retained verbatim as domain services, re-exposed as BPMN service-task delegates (`revalidate*`, `authorizePlan`, `rejectPlan`, `submitManifest`) |
| `.../application/approvals/ApprovalExpiryJob.java` | `@Scheduled` 60 s sweep: `findExpired`, resolve EXPIRED, request → `APPROVAL_EXPIRED`, REJECT_PLAN for stage 2, release reservations, audit | **Replace with Flowable** (interrupting boundary timer). Kept for `engine='LEGACY'` rows until drained, then **Remove** |
| `.../domain/approval/ApprovalRules.java` | `eligibility()` (PENDING, not expired, role, separation of duties) and `outcome()` (any REJECT → REJECTED, distinct approvers ≥ required → APPROVED) | `eligibility()` **Retain as domain logic** (called before every task completion). `outcome()` **Replace with Flowable** (loop + exclusive gateway), **Remove** in Phase 7 |
| `.../domain/approval/ApprovalRequest.java`, `ApprovalDecision.java`, `ApprovalStatus.java`, `ApprovalVerdict.java` | Domain records; `ApprovalStatus = PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED` | **Retain as domain logic** (public API shape); `ApprovalRequest` gains `processInstanceId`, `engine` |
| `.../domain/policy/ApprovalConfiguration.java`, `api/administration/ApprovalConfigurationAdminController.java`, `approval_configuration` table | Versioned approver roles, quorum, separation of duties, TTL hours (ACTIVE row) | **Retain as domain logic**: this is the "configurable approval requirements supplied by the Governance domain" and becomes process-variable input |
| `.../infrastructure/persistence/ApprovalRepository.java` | JDBC for `approval_request`, `approval_decision`, `approval_configuration`; `lockById` (FOR UPDATE), `resolve` (optimistic version), `findExpired`, `countPending` | **Adapt**: add correlation columns and queries; `findExpired` removed in Phase 7 |
| `.../api/approvals/ApprovalController.java` | `GET /api/v1/approvals`, `GET /{approvalId}`, `POST /{approvalId}/decisions`; response `ApprovalDetail{approval, decisions, requestStatus, teamId, repositoryId, environment}` | **Retain** (contract). Service behind it changes |
| `.../api/requests/RequestController.java`, `application/tracking/RequestTrackingService.java` | `GET /api/v1/requests/{id}`, `/decisions`, `/plan` | **Retain** unchanged |
| `.../application/submission/ManifestSubmissionService.java` `apply()` | Stage 1 REQUIRE_APPROVAL → `PENDING_APPROVAL_STAGE1` + `approvals.open(...)` | **Adapt**: call `ApprovalWorkflowService.open(...)` (starts the process) |
| `.../application/lifecycle/ManifestEventHandler.java` | Stage 2 on PLAN_PROPOSED (digest integrity, `lockTeam`, OPA, ALLOW/DENY/REQUIRE_APPROVAL), `DUPLICATE_PLAN`/`OUT_OF_ORDER` guards, terminal events call `approvals.cancelPending` | **Adapt** two call sites only (open, cancel). The rest is Kafka reconciliation, retained |
| `.../domain/request/RequestLifecycle.java`, `RequestStatus.java` | Request state machine (14 states) | **Retain as domain logic**: not an approval workflow; it is the reconciliation lifecycle. Flowable delegates call `requests.transition` through it |
| `.../infrastructure/kafka/ManifestCommandPublisher.java` | Builds `ManifestCommand` (SUBMIT_MANIFEST / AUTHORIZE_PLAN / REJECT_PLAN) and writes `kafka_outbox` in the caller's transaction | **Retain as infrastructure**; small adapt: return `{outboxId, commandId}` for audit |
| `.../infrastructure/kafka/OutboxRelay.java`, `OutboxRepository.java` | `@Scheduled` 500 ms, `FOR UPDATE SKIP LOCKED`, idempotent producer, backoff, FAILED | **Retain as infrastructure** (the "existing reliable publishing mechanism") |
| `.../infrastructure/kafka/ResponseManifestConsumer.java`, `InboxProcessor.java`, `InboxRetryJob.java`, `InboxRepository.java`, `DeadLetterRepository` | Consumer thread, inbox by `eventId`, retry/backoff/dead letters | **Retain as infrastructure** (not approval orchestration) |
| `.../application/usage/QuotaReservationService.java` | `lockTeam` (SELECT FOR UPDATE on team configuration), `reserve`, `release`, `@Scheduled expire()` sweep | **Retain as domain logic**; the reservation sweep is a quota TTL, not a workflow timer |
| `.../infrastructure/observability/GovernanceMetrics.java`, `MetricsRefreshJob.java` | gauge `governance.approvals.pending` (asserted by e2e) and others | **Adapt**: add workflow gauges/timers |
| `.../infrastructure/observability/Mdc.java` | MDC `requestId`, `planId` | **Adapt**: add `approvalId`, `processInstanceId` |
| `.../configuration/GovernanceProperties.java` `Workflow` record | `authorizationValidity`, `defaultApprovalTtl` (unused by the code read), `maxEvaluationAttempts`, `evaluationRetryBackoff` | **Adapt**: add `engine` mode |
| `.../infrastructure/security/TeamAuthorization.java`, `CallerContext.java`, `SecurityConfig.java` | Roles from JWT; `requireApprover`, `requireView` | **Retain as infrastructure**; this is the "trusted Governance authorization logic" for approver eligibility |
| `application/audit/AuditService.java`, `audit_record` | Business audit trail (`APPROVAL_OPENED`, `APPROVAL_DECISION`, `APPROVAL_RELEASE_STAGE1/2`, `APPROVAL_REJECTED_RELEASE`, `APPROVAL_EXPIRED`, `APPROVAL_CANCELLED`, ...) | **Retain**; actions kept, details enriched |

Custom workflow code used for non-approval processing: `InboxProcessor` retry/backoff and `ManifestEventHandler.onExhausted` (fail-closed REJECT_PLAN), `OutboxRelay` retry, `QuotaReservationService.expire`, `PolicyBundleService.verifyActivation` (local mode rollback). None of these are human workflows; all are **retained**.

### 1.3 Database and schema ownership
- Flyway `V1`..`V6` under `governance/src/main/resources/db/migration/` own every Governance table in the default schema (`public`): `manifest_request`, `request_status_history`, `governance_decision`, `proposed_plan`, `repository_state` (V1); policy tables (V2, V5); `approval_configuration`, `approval_request`, `approval_decision`, `audit_record` (V3); `kafka_outbox`, `kafka_inbox`, `kafka_dead_letter` (V4); team/usage/quota/shadow tables (V6).
- `approval_decision` has `UNIQUE (approval_id, approver_id)` (idempotent verdicts); `approval_request` has **no** uniqueness per `(request_id, stage)`; concurrency relies on `lockById` and `version`.

### 1.4 Transaction boundaries
- HTTP decide: `Blocking.inTransaction` on `gov-blocking-*` threads: row lock → verdict → resolve → release (OPA call via `PolicyEvaluationService.evaluate(...).block(30s)`) → outbox insert. One transaction, so an OPA `ERROR` rolls back the verdict and surfaces as 409 (`ApprovalRuleException`).
- Kafka: `InboxProcessor.attempt` wraps `ManifestEventHandler.handle` in one transaction (inbox mark + state + approval open + outbox).
- Expiry: `ApprovalExpiryJob` one transaction per approval.
- Kafka and PostgreSQL are never in one transaction (ADR 0002): outbox + idempotent producer + inbox.

### 1.5 Tests touching approvals
- `domain/approval/ApprovalRulesTest.java` (eligibility, quorum, rejection).
- `e2e/GovernanceEndToEndIT.java` (Testcontainers Kafka + Schema Registry + Postgres + OPA, simulators in-process): scenario 10 `productionTopicDeletionRequiresApprovalWithSeparationOfDuties` (403 without role, 409 "Separation of duties", approve → `requestStatus=PLAN_AUTHORIZED` synchronously, idempotent replay 200, 3 decisions, audit actions), scenario 11 `stageOneApprovalCanBeRejected` (→ `STAGE1_DENIED`), scenario 22 `concurrentApprovalsOfOneTeamAdmitExactlyOnePlanWithinTheQuota` (two concurrent approvals of one team: exactly one `PLAN_AUTHORIZED`), scenario `observabilityEndpointsExposeTheMetrics` (`governance_approvals_pending`). IT properties include `governance.workflow.approval-expiry-interval=5s`.
- `e2e/PolicyReleaseModeIT.java` shows the pattern for a Postgres-only `@SpringBootTest` with `governance.kafka.consumer-enabled=false` and `usage-consumer-enabled=false`.

### 1.6 Deployment
- `deploy/helm/governance/templates/deployment.yaml`: two containers (governance + OPA sidecar), `replicaCount: 2`, `RollingUpdate maxSurge 1 / maxUnavailable 0`, `terminationGracePeriodSeconds: 40`, readiness group `db,opa,policyRelease,readinessState`, `readOnlyRootFilesystem`, resources 500m/768Mi to 2/1.5Gi. `application-openshift.yml`: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=25s`.
- `governance/Dockerfile`: `eclipse-temurin:21-jre`, exec jar.

## 2. Proposed Flowable architecture [D]

```
WebFlux controllers (Netty)            Kafka consumer thread          Flowable async executor threads
        |  Blocking.inTransaction()           |  Blocking.transactional()       |  (timers only; Spring tx via same tm)
        v                                     v                                 v
ApprovalWorkflowService  <----------->  ManifestEventHandler / ManifestSubmissionService
   | open()  : insert approval_request (PENDING, engine=FLOWABLE) + runtimeService.start(businessKey=approvalId)
   | decide(): lock approval_request → eligibility → insert approval_decision → audit → taskService.complete(task, {verdict, approvedCount})
   |           (synchronous continuation: gateway → Revalidate* → Publish* delegates → request transition + kafka_outbox insert)
   | cancel(): messageEventReceived("approvalCancelled")
   v
Flowable ProcessEngine (same DataSource, same DataSourceTransactionManager)  --->  ACT_* tables (same DB, same schema)
   | delegates call: PolicyEvaluationService (OPA), ManifestRequestRepository.transition, QuotaReservationService, ManifestCommandPublisher (outbox), AuditService
   v
kafka_outbox  --OutboxRelay (unchanged)-->  requests.manifest (key teamId, ManifestCommand Avro, RecordNameStrategy)
```

Rules:
1. No Kafka client inside delegates. Delegates only write rows; `OutboxRelay` publishes.
2. No OPA logic inside BPMN. Delegates call `PolicyEvaluationService.evaluate(...)` and the gateway branches on `revalidationResult ∈ ALLOW | DENY | STALE | ERROR`.
3. Every Flowable API call from Governance code happens inside `Blocking` (gov-blocking pool or Kafka/scheduler thread), never on a Netty thread.
4. Release-path service tasks are **synchronous** (no `flowable:async`), so they execute inside `taskService.complete()` in the approver's transaction (preserves today's API semantics and the rollback-on-OPA-error behaviour). Only timers run on the async executor.
5. Governance's `approval_request` stays the public projection and correlation record (`approvalId` = Flowable business key; `process_instance_id` stored). Flowable task ids never appear in the API.

## 3. Dependency compatibility assessment [V]

| Fact | Evidence |
|---|---|
| Flowable 7.2.0 does **not** start on Spring Boot 4 (`KafkaAutoConfiguration` missing, `EventRegistryAutoConfiguration` fails) | Flowable forum thread "Spring boot 4 compatible flowable 8 release date" |
| Flowable **8.0.0** (released 2026-02-27, latest on Maven Central as of 2026-11-01; no 8.0.1 yet) is built on Spring Framework 7 / Spring Boot 4, drops Boot 3, defaults to **Jackson 3** (`tools.jackson`, with `flowable.variable-json-mapper=jackson2` fallback), JUnit 5 only, manifest `Build-Jdk-Spec: 21` | GitHub release `flowable-8.0.0`; `maven-metadata.xml`; autoconfigure jar manifest |
| `org.flowable:flowable-spring-boot-starter-process:8.0.0` depends only on `flowable-spring-boot-autoconfigure`, `flowable-engine`, `flowable-spring`, `spring-boot-starter-jdbc`. **No servlet web starter**, so it coexists with WebFlux | starter POM |
| `flowable-spring-boot-autoconfigure:8.0.0`: all REST, CMMN, DMN, IDM, LDAP, JPA, Kafka/JMS/AMQP, `spring-boot-starter-web`, actuator and `spring-boot-jackson2` dependencies are `<optional>`; `flowable-spring-security` is **not optional** (provided-scope `spring-security-core`, which Governance has) | autoconfigure POM |
| `flowable-engine:8.0.0` pulls `flowable-idm-engine` and `flowable-event-registry` (not optional) and `mybatis` | engine POM |
| Spring Boot property surface confirmed in the 8.0.0 metadata: `flowable.database-schema-update`, `flowable.database-schema`, `flowable.history-level`, `flowable.db-history-used`, `flowable.deployment-name`, `flowable.check-process-definitions`, `flowable.process-definition-location-prefix` (default `classpath*:/processes/`), `flowable.process-definition-location-suffixes` (`**.bpmn20.xml,**.bpmn`), `flowable.idm.enabled`, `flowable.eventregistry.enabled`, `flowable.async-executor-activate`, `flowable.process.async.executor.*` (lock times, acquire wait times, `global-acquire-lock-enabled`, `lock-owner`), `flowable.auto-deployment.use-lock` / `lock-name` / `lock-wait-time`, `flowable.enable-history-cleaning` / `history-cleaning-after` / `history-cleaning-cycle` | `META-INF/spring-configuration-metadata.json` of the 8.0.0 autoconfigure jar |

Decision [D]: add to `governance/pom.xml`
```xml
<dependency>
  <groupId>org.flowable</groupId>
  <artifactId>flowable-spring-boot-starter-process</artifactId>
  <version>${flowable.version}</version>   <!-- 8.0.0 managed in the root pom properties, like confluent/avro -->
</dependency>
```
with `flowable.idm.enabled=false`, `flowable.eventregistry.enabled=false` (no IDM/event-registry tables, no Kafka auto-configuration interplay) and an explicit `<exclusion>` of `flowable-spring-security` unless the Phase 2 spike shows its auto-configuration backs off cleanly with IDM disabled. The engine runs with the Jackson 3 default; Governance already uses Jackson 3. Only primitive/string process variables are used, so variable serialisation is not a compatibility surface.

Residual risk [A]: Flowable 8.0.0 was built before Spring Boot 4.1 existed; the reactor uses Boot 4.1.2 / Framework 7.0.10. Within a Boot major this is expected to work, but the first task of Phase 2 is a compile-and-start spike (`mvn -pl governance -am verify` plus a context start against Testcontainers Postgres) before anything else is built on it. Fallback if the spike fails: pin the reactor to the Boot 4.0.x line Flowable 8.0.0 targets (requires owner approval) or wait for an 8.0.x patch.

Modules deliberately **not** added: `flowable-spring-boot-starter-rest`/`-process-rest` (servlet, exposes engine REST), CMMN, DMN, `flowable-ui-*`, `flowable-spring-boot-starter-actuator` (Micrometer gauges are added by Governance itself).

## 4. BPMN process design [D]

Files under `governance/src/main/resources/processes/` (the Flowable default location; matches the repository's `src/main/resources/<area>/` convention):

### 4.1 `approval-decision.bpmn20.xml` (shared sub-process, key `governance-approval-decision`)
Called by both flows through a Call Activity; receives `approvalId, approverRoles, requiredApprovals, approvalDeadline, requesterId, separationOfDuties, approvedCount=0`; returns `approvalOutcome`.
```
start → [user task "collectVerdict": candidateGroups=${approverRoles}, dueDate=${approvalDeadline}]
      → XOR: ${verdict == 'REJECT'}                 → end "rejected"  (approvalOutcome=REJECTED)
             ${approvedCount >= requiredApprovals}  → end "approved"  (approvalOutcome=APPROVED)
             default                                 → back to "collectVerdict"   (quorum not yet reached)
```
- Exactly one open user task per approval at any time, which maps 1:1 to today's single `approval_request` row and keeps the API unchanged. Quorum is the BPMN loop + gateway, not Java.
- `verdict`, `approverId`, `approvedCount` are set by Governance on `taskService.complete()` after it has recorded the verdict in `approval_decision` (distinct approvers are counted from that table, so a replayed verdict never double counts).
- The task is intentionally **not assigned**: any eligible approver (role from the JWT, separation of duties, not already decided) may complete the current iteration. "Task assignment alone is never authorisation": `TeamAuthorization.requireApprover` + `ApprovalRules.eligibility` run first, inside the row lock.

### 4.2 `manifest-approval.bpmn20.xml` (key `governance-manifest-approval`, Stage 1)
```
start
 → callActivity "decide" (approval-decision)      boundary: interrupting timer timeDate=${approvalDeadline} → approvalOutcome=EXPIRED
                                                   boundary: interrupting message "approvalCancelled"      → approvalOutcome=CANCELLED
 → XOR on approvalOutcome
    APPROVED  → serviceTask "revalidateManifest"   ${revalidateStage1Delegate}      (digest == manifestDigest, OPA stage 1 → revalidationResult, decisionId)
                → XOR on revalidationResult
                    ALLOW → serviceTask "submitManifest"  ${submitManifestDelegate}   (request SUBMITTED, enqueueSubmit, approval APPROVED, audit APPROVAL_RELEASE_STAGE1)
                    DENY  → serviceTask "markDenied"      ${markRequestDelegate.stage1Denied(execution)}
                    ERROR → throw (transaction rollback, approver gets 409 "re-evaluation unavailable", task stays open)
    REJECTED  → serviceTask "markRejected"         ${markRequestDelegate.stage1Rejected(execution)}   (STAGE1_DENIED, approval REJECTED, audit APPROVAL_REJECTED_RELEASE)
    EXPIRED   → serviceTask "markExpired"          ${markRequestDelegate.expired(execution)}         (APPROVAL_EXPIRED, release reservations, approval EXPIRED, audit APPROVAL_EXPIRED)
    CANCELLED → serviceTask "markCancelled"        ${approvalProjection.resolve(execution, 'CANCELLED')}
 → end
```

### 4.3 `action-plan-approval.bpmn20.xml` (key `governance-action-plan-approval`, Stage 2)
```
start
 → serviceTask "bindPlan"  ${bindPlanDelegate}   (asserts proposed_plan(planId).planDigest == planDigest and == request.planId; stores nothing new; fails fast if the plan vanished)
 → callActivity "decide" (approval-decision)      same timer + message boundaries
 → XOR on approvalOutcome
    APPROVED  → serviceTask "revalidatePlan"  ${revalidateStage2Delegate}
                 (plan digest + request.planId check, freshness vs latestStateVersion → STALE, lockTeam, OPA stage 2 → ALLOW|DENY|ERROR, decisionId)
                → XOR on revalidationResult
                    ALLOW → serviceTask "authorizePlan"  ${authorizePlanDelegate}  (PLAN_AUTHORIZED, reserve, enqueueAuthorize(decisionId), approval APPROVED, audit APPROVAL_RELEASE_STAGE2)
                    DENY  → serviceTask "rejectPlan"     ${rejectPlanDelegate}     reasonCode=POLICY_DENIED (PLAN_REJECTED, enqueueReject with blocking violations)
                    STALE → serviceTask "rejectPlan"     reasonCode=STALE_PLAN     (STALE_PLAN, enqueueReject)
                    ERROR → throw (rollback as in 4.2)
    REJECTED  → serviceTask "rejectPlan"  reasonCode=APPROVAL_REJECTED   (PLAN_REJECTED, enqueueReject)
    EXPIRED   → serviceTask "rejectPlan"  reasonCode=APPROVAL_EXPIRED    (APPROVAL_EXPIRED, enqueueReject, release reservations)
    CANCELLED → serviceTask "markCancelled"
 → end
```
- `lockTeam` → evaluate → reserve → enqueue all happen in the same synchronous command, hence one database transaction, which is what makes e2e scenario 22 (two concurrent approvals of one team, exactly one admitted) hold.
- Expiry and cancellation of Stage 2 both publish REJECT_PLAN, as today (`ApprovalExpiryJob.expireOne`, `ApprovalReleaseService.rejectAfterApproval`). Cancellation after a terminal Orchestrator event does **not** publish (the request is already closed), exactly like `ApprovalService.cancelPending` today.
- Delegates are Spring beans referenced by `flowable:delegateExpression` / `flowable:expression`; no `flowable:class`, no field injection, no approver identities in XML.

### 4.4 Process variables (stable, small, immutable identifiers only)
`approvalId` (= business key), `requestId`, `teamId`, `repositoryId`, `environment`, `governanceStage` (`REQUEST | ACTION_PLAN`), `decisionId` (OPA decision that required approval), `policyRevision`, `digest` (manifest digest, stage 1), `planId`, `planDigest` (stage 2), `approvalPolicyId` (`approval_configuration.id`), `requiredApprovals`, `approverRoles`, `separationOfDuties`, `requesterId`, `approvalDeadline` (Date), `approvedCount`, `verdict`, `approverId`, `approvalOutcome`, `revalidationResult`, `revalidationDecisionId`, `authoritative` (false in shadow mode, section 16 Phase 3). No manifest, no action list.

## 5. Mapping of existing code to Flowable components

| Today | Flowable / new |
|---|---|
| `ApprovalService.open()` | `ApprovalWorkflowService.open(...)`: insert `approval_request` (PENDING, `engine='FLOWABLE'`), `runtimeService.createProcessInstanceBuilder().processDefinitionKey(...).businessKey(approvalId).variables(...).start()`, store `process_instance_id`, audit `APPROVAL_OPENED` with `processInstanceId` |
| `ApprovalService.decide()` quorum + `approvals.resolve` + `release.*` | `ApprovalWorkflowService.decide(...)`: `lockById`, `ApprovalRules.eligibility`, `insertDecision`, audit, `approvedCount` = distinct APPROVE approvers, `taskService.complete(activeTaskByBusinessKey(approvalId), vars)`; BPMN does the rest synchronously; re-read `approval_request` and `manifest_request` for the response |
| `ApprovalRules.outcome()` | gateway in `approval-decision` |
| `ApprovalReleaseService.releaseStage1/2`, `rejectAfterApproval` | `RevalidateStage1Delegate`, `RevalidateStage2Delegate`, `SubmitManifestDelegate`, `AuthorizePlanDelegate`, `RejectPlanDelegate`, `MarkRequestDelegate` (`application/approvals/workflow/`); same code, same reasons and audit actions |
| `ApprovalExpiryJob` | boundary timer `timeDate=${approvalDeadline}` on the call activity; `expires_at` column kept for the API |
| `ApprovalService.cancelPending()` | `ApprovalWorkflowService.cancel(requestId, reason)`: for each PENDING approval of the request: `runtimeService.createExecutionQuery().processInstanceBusinessKey(id).messageEventSubscriptionName("approvalCancelled").singleResult()` → `messageEventReceived(...)` |
| `approval_request.status` | still written by `ApprovalProjection.resolve(execution, status)` (delegate) so list/get and `governance.approvals.pending` are unchanged |
| manual transitions `requests.transition(...)` | unchanged repository call from delegates, still guarded by `RequestLifecycle.canTransition` |
| MDC `requestId/planId` | `Mdc.of(requestId, planId, approvalId, processInstanceId)` set in `ApprovalWorkflowService` and by a process-level `ExecutionListener` for timer-driven paths |

## 6. Database schema and migration strategy [D]

**Recommendation: same datasource, same database, same `public` schema; Flowable owns the `ACT_*` tables, Flyway owns everything else.**
- Why not a dedicated datasource: it would put the engine in a different connection and transaction than `approval_decision`, `manifest_request` and `kafka_outbox`, breaking the single-transaction property that the whole design (and e2e scenario 22) relies on.
- Why not a dedicated PostgreSQL schema on the same datasource: Flowable's DDL/DML is unqualified and relies on `search_path`; it would need a second connection pool or per-connection `SET search_path`, and Flyway would need `defaultSchema`/`schemas` tuning. Possible, but more moving parts for no safety gain given the `ACT_` prefix. Record the choice in **ADR 0011** with the dedicated-schema option as the documented alternative.
- Schema lifecycle: `flowable.database-schema-update=true` (engine creates/upgrades `ACT_*` at startup using its own version table `ACT_GE_PROPERTY`), plus `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` setting `setUseLockForDatabaseSchemaUpdate(true)` so two pods starting on an empty database cannot race (the Helm rolling update already starts one new pod at a time). The governance DB user already has DDL rights (Flyway). Optional for restricted environments [A]: `database-schema-update=false` and DBA-applied `org/flowable/db/create/flowable.postgres.create.*.sql` from the engine jar.
- Flyway and Flowable never touch each other's tables; `flyway clean` is not used anywhere in the repository. Document the rule "never add `ACT_*` to Flyway, never add Governance tables through Flowable".
- Governance-side migration `V7__flowable_workflow_correlation.sql`:
  - `ALTER TABLE approval_request ADD COLUMN process_instance_id TEXT, ADD COLUMN engine TEXT NOT NULL DEFAULT 'LEGACY';`
  - `CREATE UNIQUE INDEX uq_approval_request_process ON approval_request (process_instance_id) WHERE process_instance_id IS NOT NULL;`
  - `CREATE UNIQUE INDEX uq_approval_request_open ON approval_request (request_id, stage) WHERE status = 'PENDING';` (idempotency belt: one open approval per request and stage)
  - `CREATE INDEX ix_approval_request_engine_pending ON approval_request (engine) WHERE status = 'PENDING';` (drain gauge)
- History: `flowable.history-level=audit` (process, activity, task, variable history in `ACT_HI_*`). Retention: enable `flowable.enable-history-cleaning=true` with `history-cleaning-after=P365D` [A: confirm with audit owners]; the business audit trail (`audit_record`) is retained independently and remains authoritative.
- Recovery after restart: process state, timers (`ACT_RU_TIMER_JOB`) and tasks are rows; the async executor re-acquires due timers after startup. Jobs whose lock owner died are re-acquired after `timer-lock-time` (set `PT5M`, default) or immediately with `unlock-owned-jobs=true` on shutdown.

## 7. Kafka integration changes

None on the wire. Topics, `ManifestCommand` / `ManifestEvent` Avro (`contracts/src/main/avro/*.avsc`), `RecordNameStrategy`, teamId key, idempotent producer and inbox stay as they are.
- Stage 1: `SubmitManifestDelegate` → `ManifestCommandPublisher.enqueueSubmit(request)` (outbox) → `OutboxRelay`.
- Stage 2: `ManifestEventHandler.onPlanProposed` REQUIRE_APPROVAL branch → `ApprovalWorkflowService.open(...)` (same inbox transaction). `AuthorizePlanDelegate` → `enqueueAuthorize(request, plan, revalidationDecisionId)`; `RejectPlanDelegate` → `enqueueReject(request, planId, planDigest, decisionId, reasonCode, violations)`.
- Final status: `ManifestEventHandler.terminal` unchanged except `approvals.cancelPending` → `workflow.cancel`.
- Duplicate/replay protection unchanged (`kafka_inbox` by eventId, `DUPLICATE_PLAN`, `OUT_OF_ORDER`), plus the new partial unique index on open approvals and the business-key uniqueness check in `open()`.
- Small adapt: `ManifestCommandPublisher.enqueue*` return `EnqueuedCommand(outboxId, commandId)` so audit details and Flowable variables can carry the resulting Kafka `commandId` (section 15).

## 8. OPA integration boundaries

- OPA stays the only policy decision point: Stage 1 and Stage 2 initial evaluations are untouched (`ManifestSubmissionService`, `ManifestEventHandler`); `REQUIRE_APPROVAL` is what starts a process.
- Revalidation after approval (`RevalidateStage1Delegate` / `RevalidateStage2Delegate`) calls `PolicyEvaluationService.evaluate(stage, request, Set.of(), plan)` exactly as `ApprovalReleaseService` does now (exceptions included, usage/quota included, policy release verified); the decision is persisted with `decisions.insert` and its id becomes `revalidationDecisionId` → `decisionId` on `AUTHORIZE_PLAN`.
- BPMN conditions only read `revalidationResult` and `approvalOutcome`. No Rego semantics in XML; no policy ids in XML.
- Stored context: `decisionId`, `policyRevision`, `requestId`, `planId`, `planDigest`, `digest` (section 4.4). Expired exceptions are naturally handled because revalidation re-resolves exceptions at release time (current behaviour).

## 9. REST API compatibility plan

| Endpoint | Change |
|---|---|
| `GET /api/v1/approvals?status&teamId&limit` | none (reads `approval_request`) |
| `GET /api/v1/approvals/{approvalId}` | none; `ApprovalRequest` gains optional `processInstanceId` and `engine` fields (additive) |
| `POST /api/v1/approvals/{approvalId}/decisions` | same body `{verdict, comment}`, same `ApprovalDetail` response, same synchronous `requestStatus`, same 403/404/409 mapping. New: Flowable exceptions (`FlowableObjectNotFoundException`, `FlowableOptimisticLockingException`) mapped in `api/ApiExceptionHandler.java` to 409 `APPROVAL_CONFLICT`, never leaked |
| `GET /api/v1/requests/{requestId}`, `/decisions`, `/plan` | none |
| `/api/v1/admin/approval-configurations` | none (still the source of approver roles, quorum, SoD, TTL) |
| OpenAPI (`springdoc`) | regenerated; additive only |
| Admin UI (`admin-ui/src/api/types.ts`) | no change required (it consumes approval configurations, not approvals) |

## 10. OpenShift deployment impact

- Pod shape unchanged: governance container + OPA sidecar; no new container, no new Service/Route.
- Startup: Flowable schema check/create (locked), BPMN auto-deployment (`flowable.auto-deployment.use-lock=true` so concurrent pods do not create duplicate deployments), then the engine starts its async executor. Add `WorkflowEngineHealthIndicator` (engine up, both process definition keys deployed) to the readiness group `db,opa,policyRelease,workflowEngine,readinessState` in `application.yml`.
- Graceful shutdown: set `asyncExecutorSecondsToWaitOnShutdown=20` (Flowable default is 60, which exceeds `timeout-per-shutdown-phase=25s` and the 40 s grace period) and `unlock-owned-jobs=true` in `application-openshift.yml` via `flowable.process.async.executor.*` / configurer.
- Multi-replica: job acquisition uses Flowable's lock owner + lock expiration columns; enable `flowable.process.async.executor.global-acquire-lock-enabled=true` to reduce contention between the two replicas. Application-level idempotency remains: outbox rows are only ever inserted inside a committed transaction, and `requests.transition` is optimistic (`version`).
- Resources: the engine adds MyBatis caches and the async executor pool (default 8 threads). Raise `governance.resources` to requests 500m/1Gi, limits 2/2Gi in `values.yaml` and re-measure [A]. `JAVA_TOOL_OPTIONS` unchanged.
- Timer precision: `default-timer-job-acquire-wait-time=PT10S` is fine for hour-scale TTLs.
- Rolling upgrade rule (section 11): a new BPMN version may only reference delegate beans that exist in **both** the old and the new image during the rolling window; otherwise ship the beans first (additive release), the BPMN change second.
- Helm: new values `governance.workflow.engine` (rendered into the governance ConfigMap through the existing `governance.config` hook), resources bump, `deploy/helm/scripts/render-check.sh` must stay green. Add Prometheus alert rules for `governance_workflow_jobs_deadletter > 0` and `governance_approvals_legacy_pending` to the runbook.

## 11. Workflow deployment and versioning strategy [D]

- Definitions live in Git under `governance/src/main/resources/processes/`; deployed on startup by Flowable auto-deployment (`flowable.deployment-name=governance-approvals`). Flowable creates a new deployment/version only when resource bytes changed; running instances **stay on the version they started with** (engine default). Nothing migrates automatically.
- Each BPMN carries a `flowable:versionTag`/documentation line with the Git change; definition keys are stable (`governance-manifest-approval`, `governance-action-plan-approval`, `governance-approval-decision`).
- Compatibility contract between versions: variable names in section 4.4 are append-only; delegate bean names are stable; a Call Activity binds to the **latest** `approval-decision` version at the time the parent reaches it (Flowable default), so changes to the shared sub-process must stay backward compatible with in-flight parents or use an explicit `calledElementVersion`-style pin (Flowable supports `flowable:calledElementType` and `flowable:fallbackToDefaultTenant`; the plan pins by keeping the sub-process contract stable).
- Controlled upgrades of in-flight instances: only through an explicit, reviewed `ProcessInstanceMigrationBuilder` script executed by an admin command (not on startup) after a dry run with `validateMigration`. Default policy: let instances finish on their version (TTL ≤ 720 h).
- Rollback: redeploying the previous image redeploys the previous XML as a **new** version (Flowable versions are monotonic); instances started on the newer version keep running on it, which is why delegate beans may only be removed one release after their last BPMN reference. Historical definitions are never deleted (`repositoryService.deleteDeployment` is not used).
- Test `BpmnVersionUpgradeIT` (section 13) proves: instance started on v1 completes on v1 after v2 is deployed; new instances use v2.

## 12. In-flight approval migration strategy [D]

- Every `approval_request` row carries `engine` (`LEGACY` for all existing rows via V7 default; `FLOWABLE` for rows opened while the flag is `flowable`).
- `ApprovalWorkflowService.decide` dispatches on `engine`: LEGACY rows keep using today's `ApprovalService.decide` path (quorum in Java, `ApprovalReleaseService`), FLOWABLE rows complete tasks. `ApprovalExpiryJob` is restricted to `engine='LEGACY'`. Both paths share eligibility, audit and the publisher, so no pending approval is abandoned or silently discarded.
- **Default: drain.** Legacy rows resolve by decision, expiry (TTL from their own `expires_at`, max 720 h) or cancellation. Gauge `governance.approvals.legacy_pending` (new) and the cleanup gate `SELECT count(*) FROM approval_request WHERE engine='LEGACY' AND status='PENDING'` = 0 decide when Phase 7 may start.
- **Optional accelerator** (decision for the owners): an admin-only `POST /api/v1/admin/approvals/migrate-legacy` that, per PENDING legacy row, starts the matching process with `approvalDeadline = expires_at`, `approvedCount` = distinct existing APPROVE verdicts, sets `engine='FLOWABLE'`, `process_instance_id`, and audits `APPROVAL_MIGRATED`. Idempotent (skips rows already migrated). Recommended only if the drain window is unacceptable.

## 13. Test strategy

Unit (JUnit 5, Mockito, surefire):
- `ApprovalRulesTest` keeps eligibility cases; quorum cases move to the engine test below (`outcome()` is removed in Phase 7).
- Delegate unit tests with mocked `PolicyEvaluationService`, `ManifestRequestRepository`, `ManifestCommandPublisher`, `QuotaReservationService`: result variable per OPA outcome, exception on ERROR, no publish on DENY/STALE.

Real engine integration (failsafe `*IT`, Testcontainers Postgres only, Kafka consumers disabled as in `PolicyReleaseModeIT`, `PolicyEvaluationService` replaced by a controllable test bean, OPA not needed), new class `governance/src/test/java/com/itland/governance/approvals/ApprovalWorkflowIT.java` (+ helpers in `support/`):
- deployment: three definitions deployed once, redeploy without change creates no new version;
- manifest approval: single approver → `SUBMITTED` + one `kafka_outbox` row `SUBMIT_MANIFEST`; rejection → `STAGE1_DENIED`, no outbox row;
- action-plan approval: approve → `PLAN_AUTHORIZED`, reservation row, outbox `AUTHORIZE_PLAN` with the revalidation `decisionId`; reject → `PLAN_REJECTED` + `REJECT_PLAN APPROVAL_REJECTED`; digest mismatch (plan replaced) → `IllegalStateException`, verdict rolled back; stale state version → `STALE_PLAN` + `REJECT_PLAN STALE_PLAN`; OPA DENY on revalidation → `PLAN_REJECTED POLICY_DENIED`; OPA ERROR → 409, no `approval_decision`, no outbox, task still active (PostgreSQL rollback test);
- multiple approvers and quorum (`requiredApprovals=2`): first approve keeps PENDING and a new task iteration; second approve releases; same approver twice counts once; REJECT after one APPROVE → REJECTED;
- separation of duties: requester with approver role → 409, task untouched;
- duplicate approval requests: second `open()` for the same request/stage while PENDING → unique index violation handled, no second process instance; replayed `open()` after resolution is a no-op;
- concurrent decisions: two threads complete the same approval simultaneously → exactly one verdict applied per approver, no `FlowableOptimisticLockingException` leaks (row lock serialises);
- expiration: Flowable clock advanced past `approvalDeadline`, timer job executed via `ManagementService` → `APPROVAL_EXPIRED`, `REJECT_PLAN APPROVAL_EXPIRED` for stage 2, reservations released, audit `APPROVAL_EXPIRED`;
- cancellation: `cancel(requestId)` → process ends via message boundary, approval `CANCELLED`, no publish;
- restart recovery: stop the async executor, create a pending approval with a due timer, build a **second** `ProcessEngine` on the same datasource (simulating a new pod), start it: the timer fires exactly once (audit count), task state intact;
- multi-replica: two engines on one database with timers due at the same time: each timer executes once;
- BPMN version upgrade (`BpmnVersionUpgradeIT`): deploy modified `approval-decision` via `repositoryService`, in-flight parent completes on its original version;
- Kafka outbox recovery: outbox row written by a delegate is relayed by `OutboxRelay` on the next poll (reuse `OutboxRelay.relayBatch()` with a Testcontainers Kafka in the e2e suite).

End-to-end (`GovernanceEndToEndIT`, Kafka + OPA + simulators): switch to `governance.workflow.engine=flowable` in Phase 4 and keep scenarios 10, 11, 22 and the metrics scenario green unchanged (this is the API compatibility proof). Add: stage-2 expiry publishes `REJECT_PLAN APPROVAL_EXPIRED` and the Orchestrator stand-in drops the plan (needs a short deadline: add a test-only `governance.workflow.approval-ttl-override` duration consulted by `open()` [D]); replayed `PLAN_PROPOSED` while an approval is pending creates no second approval; shadow-mode comparison (Phase 3) reports zero mismatches over the scenario set.

Tooling: Flowable's `FlowableSpringExtension`/`ProcessEngine` test clock for time travel; `ManagementService.moveTimerToExecutableJob` + `executeJob` for deterministic timer tests; Awaitility already present.

## 14. Phased implementation tasks (ordered)

### Phase 1, Inventory — done (this document, sections 1 and 5)
Acceptance: every component in 1.2 classified; owners confirm the `[A]` items in section 17.

### Phase 2, Flowable foundation — 3 to 4 days
1. Root `pom.xml`: `<flowable.version>8.0.0</flowable.version>` property. `governance/pom.xml`: `flowable-spring-boot-starter-process` (+ exclusion decided by the spike).
2. Spike: context starts against Testcontainers Postgres with `flowable.idm.enabled=false`, `flowable.eventregistry.enabled=false`; record findings in the ADR.
3. `configuration/FlowableConfig.java`: `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` (schema update lock, `asyncExecutorSecondsToWaitOnShutdown=20`, history level, event listener for job metrics, `Clock` bridge so tests can move both clocks). `application.yml` / `application-openshift.yml`: Flowable properties (section 6, 10), `governance.workflow.engine: legacy`.
4. `db/migration/V7__flowable_workflow_correlation.sql` (section 6); `ApprovalRepository` + `ApprovalRequest` gain `processInstanceId`, `engine`.
5. BPMN files (section 4) with a `BpmnDeploymentIT` proving they parse and deploy.
6. Delegates under `application/approvals/workflow/` (`RevalidateStage1Delegate`, `RevalidateStage2Delegate`, `SubmitManifestDelegate`, `AuthorizePlanDelegate`, `RejectPlanDelegate`, `MarkRequestDelegate`, `BindPlanDelegate`, `ApprovalProjection`) extracted from `ApprovalReleaseService` (which keeps delegating to the same code so the legacy path is unchanged).
7. `WorkflowEngineHealthIndicator`; `GovernanceMetrics` + `MetricsRefreshJob` new meters; `Mdc` extension; `ApiExceptionHandler` mappings.
8. ADR 0011 (`docs/adr/0011-embedded-flowable-approvals.md`), `docs/governance.md` Approvals section, `docs/runbook.md` workflow section.
Acceptance: `mvn -q verify` green with `engine=legacy`; `ACT_*` tables created once; three definitions deployed v1; readiness includes `workflowEngine`; existing e2e unchanged.

### Phase 3, Parallel verification (shadow) — 3 days
1. `ApprovalWorkflowService` (open / decide / cancel) with the `engine` dispatch and the `authoritative` variable.
2. `ReleaseGateway` seam: `AuthoritativeReleaseGateway` (does the real transition/publish) and `ShadowReleaseGateway` (records `WORKFLOW_SHADOW_<step>` audit rows and `governance.workflow.shadow.outcomes{stage,outcome,match}`; never transitions or publishes). Delegates call the gateway; the gateway is chosen from `authoritative`.
3. `engine=shadow`: legacy stays authoritative; every open/decide/cancel is mirrored into a process instance in the same transaction; a comparison on resolution records match/mismatch between `approval_request.status` (legacy) and `approvalOutcome` (Flowable).
4. `ApprovalWorkflowIT` (section 13) running the engine authoritatively in isolation.
Acceptance: e2e under `shadow` reports zero mismatches; exactly one outbox command per release (legacy only); `ApprovalWorkflowIT` green.

### Phase 4, Workflow migration — 3 days
1. `engine=flowable`: new approvals open process instances and the authoritative gateway; legacy path only for `engine='LEGACY'` rows.
2. `ManifestSubmissionService.apply` and `ManifestEventHandler.onPlanProposed/terminal` call `ApprovalWorkflowService`.
3. `ApprovalController` wired to `ApprovalWorkflowService`; response unchanged.
4. e2e switched to `engine=flowable`; new scenarios (stage-2 expiry, replay while pending).
Acceptance: all e2e scenarios green under `flowable`; API responses byte-compatible except additive fields; no `ApprovalExpiryJob` activity for FLOWABLE rows.

### Phase 5, In-flight handling — 1 day (+1 if the accelerator is wanted)
1. Gauge `governance.approvals.legacy_pending`; runbook drain procedure; cleanup gate query.
2. Optional `migrate-legacy` admin command (section 12).
Acceptance: legacy PENDING rows still decidable and expirable; migrated rows (if used) continue in Flowable with their original deadline and counted verdicts.

### Phase 6, Cutover — 0.5 day + soak
1. `governance.workflow.engine: flowable` becomes the default in `application.yml`; Helm values per environment (dev → staging → production), each behind the existing deploy workflow and readiness.
2. Dashboards/alerts for the new meters.
Acceptance: production replicas run `flowable`; zero dead-letter jobs after 48 h; legacy pending count trending to zero.

### Phase 7, Cleanup — 1 day, only after the drain gate is 0
1. Remove `ApprovalExpiryJob`, `ApprovalRules.outcome()`, `ApprovalRepository.findExpired`, the legacy branch of `ApprovalService.decide` (class retained for list/get/eligibility or folded into `ApprovalWorkflowService`), `ApprovalReleaseService` (its logic now lives in the delegates), the `shadow` mode and `ShadowReleaseGateway`, `governance.workflow.approval-expiry-interval` from the IT properties, quorum unit tests superseded by `ApprovalWorkflowIT`.
2. Keep `engine` column (history), `approval_request` / `approval_decision` tables and all `audit_record` rows.
Acceptance: `mvn -q verify` green; no `@Scheduled` approval sweep left; docs and ADR updated.

## 15. File-by-file change plan

| File | Action | Phase | Why |
|---|---|---|---|
| `pom.xml` | UPDATE | 2 | `flowable.version` property |
| `governance/pom.xml` | UPDATE | 2 | `flowable-spring-boot-starter-process` dependency (+ exclusion per spike) |
| `governance/src/main/resources/application.yml` | UPDATE | 2, 6 | `flowable.*` properties, `governance.workflow.engine`, readiness group `workflowEngine` |
| `governance/src/main/resources/application-openshift.yml` | UPDATE | 2 | async executor shutdown/lock settings |
| `governance/src/main/resources/db/migration/V7__flowable_workflow_correlation.sql` | CREATE | 2 | correlation columns and idempotency indexes |
| `governance/src/main/resources/processes/approval-decision.bpmn20.xml` | CREATE | 2 | shared verdict loop |
| `governance/src/main/resources/processes/manifest-approval.bpmn20.xml` | CREATE | 2 | Stage 1 flow |
| `governance/src/main/resources/processes/action-plan-approval.bpmn20.xml` | CREATE | 2 | Stage 2 flow |
| `governance/src/main/java/com/itland/governance/configuration/FlowableConfig.java` | CREATE | 2 | engine configurer, clock bridge, job event listener |
| `.../configuration/GovernanceProperties.java` | UPDATE | 2 | `Workflow.engine` (`legacy | shadow | flowable`), `approvalTtlOverride` (tests) |
| `.../application/approvals/workflow/ApprovalWorkflowService.java` | CREATE | 3 | open / decide / cancel, engine dispatch |
| `.../application/approvals/workflow/{RevalidateStage1Delegate,RevalidateStage2Delegate,SubmitManifestDelegate,AuthorizePlanDelegate,RejectPlanDelegate,MarkRequestDelegate,BindPlanDelegate,ApprovalProjection}.java` | CREATE | 2 | BPMN service-task implementations |
| `.../application/approvals/workflow/{ReleaseGateway,AuthoritativeReleaseGateway,ShadowReleaseGateway}.java` | CREATE | 3 | authoritative vs shadow seam (shadow removed in 7) |
| `.../application/approvals/ApprovalReleaseService.java` | UPDATE then DELETE | 2, 7 | delegate to shared release logic; removed after drain |
| `.../application/approvals/ApprovalService.java` | UPDATE | 3, 4, 7 | legacy-only decide path; list/get/eligibility retained |
| `.../application/approvals/ApprovalExpiryJob.java` | UPDATE then DELETE | 4, 7 | `engine='LEGACY'` only; removed after drain |
| `.../domain/approval/ApprovalRequest.java` | UPDATE | 2 | `processInstanceId`, `engine` |
| `.../domain/approval/ApprovalRules.java` | UPDATE | 7 | remove `outcome()` |
| `.../infrastructure/persistence/ApprovalRepository.java` | UPDATE | 2, 5, 7 | new columns, `findActiveTaskless`/`countLegacyPending`, drop `findExpired` |
| `.../application/submission/ManifestSubmissionService.java` | UPDATE | 4 | REQUIRE_APPROVAL → workflow open |
| `.../application/lifecycle/ManifestEventHandler.java` | UPDATE | 4 | REQUIRE_APPROVAL → workflow open; terminal → workflow cancel |
| `.../infrastructure/kafka/ManifestCommandPublisher.java` | UPDATE | 2 | return `{outboxId, commandId}` |
| `.../api/approvals/ApprovalController.java` | UPDATE | 4 | inject `ApprovalWorkflowService`; response unchanged |
| `.../api/ApiExceptionHandler.java` | UPDATE | 2 | map Flowable exceptions to 409/404 |
| `.../infrastructure/observability/GovernanceMetrics.java`, `MetricsRefreshJob.java` | UPDATE | 2, 5 | workflow meters, legacy pending gauge |
| `.../infrastructure/observability/Mdc.java` | UPDATE | 2 | `approvalId`, `processInstanceId` |
| `.../infrastructure/observability/WorkflowEngineHealthIndicator.java` | CREATE | 2 | readiness contributor |
| `governance/src/test/java/com/itland/governance/approvals/ApprovalWorkflowIT.java`, `BpmnVersionUpgradeIT.java`, `BpmnDeploymentIT.java` | CREATE | 2, 3 | real engine tests |
| `governance/src/test/java/com/itland/governance/domain/approval/ApprovalRulesTest.java` | UPDATE | 7 | eligibility only |
| `governance/src/test/java/com/itland/governance/e2e/GovernanceEndToEndIT.java` | UPDATE | 3, 4 | `engine` property, new scenarios |
| `governance/src/test/java/com/itland/governance/support/{PlatformContainers,TestSecurityConfig}.java` | UPDATE | 3 | engine test support (clock, OPA stub) |
| `deploy/helm/governance/values.yaml`, `values-*.yaml`, `templates/configmap-governance.yaml` | UPDATE | 2, 6 | resources, `governance.workflow.engine` per environment |
| `deploy/helm/governance/README.md`, `docs/runbook.md`, `docs/governance.md`, `README.md`, `docs/flow-animation.html` | UPDATE | 2, 6, 7 | operations and architecture docs |
| `docs/adr/0011-embedded-flowable-approvals.md` | CREATE | 2 | decision record |

## 16. Acceptance criteria per phase
See the "Acceptance" line under each phase in section 14. Programme-level acceptance maps to the request's 15 criteria: 1, 2, 5, 11 → Phase 2/3 ITs; 3, 9, 10 → e2e scenarios 10/11/22 + replay scenario under `flowable`; 4, 7, 8 → revalidation delegates and the fact that only `AuthorizePlanDelegate` writes `AUTHORIZE_PLAN`; 6 → V7 columns; 12 → multi-engine IT + rolling-update rule; 13 → Phase 5; 14, 15 → Phase 7 gate.

## 17. Risks and unresolved decisions

Risks:
1. **Flowable 8.0.0 vs Spring Boot 4.1.2**: only 8.0.0 exists and it predates Boot 4.1. Mitigation: Phase 2 spike first; fallback pin or wait for 8.0.x.
2. `flowable-spring-security` and `flowable-idm-engine` are pulled transitively; with IDM disabled their auto-configuration must back off. Mitigation: exclusion + spike.
3. Synchronous release inside `taskService.complete()` keeps today's API semantics but means the HTTP call holds a DB transaction across an OPA call (as today, 30 s timeout). No regression, but documented.
4. Async executor threads do blocking JDBC/OPA work: fine (they are not Netty threads), but they bypass the `gov-blocking` pool; keep the executor small (8) and size Hikari accordingly (`GOVERNANCE_DB_POOL` 16 today).
5. Timer-driven paths (expiry) can hit OPA/DB failures: Flowable retries (default 3) then dead-letters the job; a dead-lettered expiry means the Orchestrator keeps the plan pending. Mitigation: `governance.workflow.jobs.deadletter` alert + runbook `managementService.moveDeadLetterJobToExecutableJob`.
6. Schema DDL at startup requires DDL rights on the governance database (true today for Flyway).
7. `ACT_HI_*` growth; mitigation: history cleaning (section 6).
8. BPMN change discipline during rolling updates (section 11); mitigation: PR checklist in `docs/runbook.md`.

Decisions needed from the owners before Phase 2 ends:
- D1 Same schema with `ACT_*` prefix (recommended) versus dedicated PostgreSQL schema.
- D2 In-flight approvals: drain only (recommended) or also build the migrate-legacy accelerator.
- D3 Keep the release synchronous in the approver's HTTP call (recommended, preserves the e2e-asserted contract) or make it an async job (changes `requestStatus` semantics of `POST /decisions`).
- D4 History retention period for `ACT_HI_*` (proposed 365 days) and whether Flowable history is exposed anywhere (proposed: not in the public API; auditors use `audit_record`).
- D5 Expose `processInstanceId` in the approval API (proposed: yes, additive, admins/auditors only if preferred).
- D6 Resource bump for the pods (proposed 1Gi/2Gi) pending measurement.

## 18. Verification commands
```bash
# Build, unit tests and all Testcontainers ITs (Docker required)
mvn -q verify
# Engine ITs only
mvn -q -pl governance -am verify -Dit.test='ApprovalWorkflowIT,BpmnVersionUpgradeIT,BpmnDeploymentIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
# End-to-end under each engine mode
mvn -q -pl governance -am verify -Dit.test=GovernanceEndToEndIT -Dgovernance.workflow.engine=shadow
mvn -q -pl governance -am verify -Dit.test=GovernanceEndToEndIT -Dgovernance.workflow.engine=flowable
# Schema ownership check on a running stack (Postgres published on 15432 by deploy/docker-compose.yml)
psql postgresql://governance:governance@localhost:15432/governance -c "\dt act_*" -c "select version, description from flyway_schema_history order by installed_rank"
# Definitions deployed and readiness
curl -s localhost:8080/actuator/health/readiness | jq .components.workflowEngine
curl -s localhost:8080/actuator/prometheus | grep -E 'governance_(approvals_pending|approvals_legacy_pending|workflow_)'
# Helm charts still render and validate
deploy/helm/scripts/render-check.sh
# Local stack smoke test (stage 2 approval path)
docker compose -f deploy/docker-compose.yml up --build -d && deploy/scripts/upload-release.sh && deploy/scripts/submit-manifest.sh docs/examples/manifest.json
```
