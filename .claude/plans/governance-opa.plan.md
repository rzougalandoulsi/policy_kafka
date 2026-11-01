# Plan: Governance Microservice with OPA

**Source**: `/ecc:plan` free-form requirements (Governance + OPA for the Kafka resource-management platform)
**Complexity**: Large
**Status**: IMPLEMENTED (2026-10-31); see README.md and docs/. Deviation on user request: the Orchestrator is NOT implemented, a simulator (`orchestrator-simulator/`) stands in for it like the Processor; required Orchestrator changes are documented in docs/orchestrator-integration.md.

---

## 0. Repository analysis (mandatory, section 3 of the brief)

The working directory `policy_kafka/` is **empty**. The only existing platform code on this machine is the
Orchestrator prototype at `/Users/abderrazaklandoulsi/Documents/codes/kafka/ssengine` (not a git repo).
**No Processor source exists anywhere on the machine.**

| # | Item | Finding |
|---|---|---|
| 1 | Maven modules / packages | Single module `com.itland:ssengine:1.0`, parent `spring-boot-starter-parent:3.4.5`, Java 21, package `com.itland.orchestrator.{config,diff,producer,topology}`, Avro generated into `com.itland.orchestrator.avro`. `avro-maven-plugin` 1.11.3 with `stringType=String`. No tests, no Dockerfile, no compose. |
| 2 | Kafka topics | `teams` (KTable, onboarding, unused), `<team>.repo.manifest` (regex source, desired manifests, **directly ingested by Streams = the governance bypass to close**), `teams.repo.manifest` (**compacted confirmed-state table keyed `teamId\|repoId`, NOT a command topic**), `teams.changes` (ACTION records to Processor + END_OF_BATCH markers for Orchestrator, header `intendedFor`), `request.status` (Processor → `ProcStatus`, keyed by correlationId), `<team>.status` (fan-out of per-action feedback). |
| 3 | Avro schemas | `Action{type, payload map}`, `EndOfBatch{type, correlationId, expectedCount}`, `ProcStatus{correlationId, teamId, resourceType, resourceName, success, payload}`, `BatchState{teamId, repoId, statuses[], totalCount}`, `RepoConfirmed{teamId, repoId, topics[], pools[], rbacsKey[]}`, `RepoDesired{teamId, repoId}`. **`RepoDesired` is incomplete: `ManifestDiffer` calls `getTopics()`/`getIdentityPools()` which do not exist, so the prototype does not compile against its own schemas.** `tmp` holds an older JSON-record version with the intended model: `TopicSpec{name, partitions, config}`, `PoolSpec{name, principals[], rbacs[]}`, `RbacSpec{principal, resource, role}`. |
| 4 | Manifest structure | Desired: `{teamId, repoId, topics[TopicSpec], identityPools[PoolSpec]}`. No `environment` field today. The brief's example (`resources.topics[{name,partitions,retentionMs}]`, `resources.rbac[{principal,role}]`) maps onto this (retentionMs → `config.retention.ms`; rbac → pool-less RBAC entries). |
| 5 | Repo vs team semantics | Confirmed state is stored per `teamId\|repoId`; a repo manifest is diffed only against its own confirmed record, so it cannot delete another repo's resources. Team-level manifests do not exist. |
| 6 | Validation rules | None. No structural validation, no naming rules. |
| 7 | Streams topology / stores | Stores: `confirmed-manifests` (KTable of `teams.repo.manifest`), `batch-accumulator` (by correlationId), `expected-by-corr`. Subtopologies: (a) desired-stream re-keyed `teamId\|repoId` left-joined with confirmed (auto repartition), (b) status aggregation by correlationId joined with expected counts, (c) reconciled → `teams.repo.manifest`. EOS v2, 2 threads. Bugs: END_OF_BATCH read via `SpecificAvroSerde<Action>`; `SpecificAvroSerde` never configured with the registry URL; feedback stream never `.to()`-ed; `diffAndActions` is called twice per message producing two different correlationIds. |
| 8 | Partitioning | Message key `teamId\|repoId` (string). `request.status` keyed by correlationId. No custom partitioner. |
| 9 | Correlation ids | `correlationId` = random UUID generated inside the diff; no requestId or planId concept. |
| 10 | Final status messages | Per-action `ProcStatus` fanned out to `<team>.status`; reconciled `RepoConfirmed` to `teams.repo.manifest`. No request-level terminal event. |
| 11 | Error handling / retry | None. `ActionBatchProducer` wraps in `RuntimeException`; no DLQ; no deserialization error handler. |
| 12 | Security | None (no auth, no ACLs, plaintext localhost). |
| 13 | Deployment | `application.yml` only (localhost:9092 / 8081). User has Confluent `cp-all-in-one` (cp-server/schema-registry 8.0.0) in a sibling folder, usable as a compose reference. |

Local toolchain: Java 21.0.6, Maven 3.9.9, Docker 29 + Compose v5, **no local `opa` binary** (use the `openpolicyagent/opa` image), no `psql`. Maven Central and Confluent repos reachable. Spring Boot 4.1.2 parent and core starters already cached.

### Patterns to mirror (from the Orchestrator and the user's related KafkaToolsUI backend)

| Category | Source | Pattern |
|---|---|---|
| Naming | `ssengine/src/main/java/com/itland/orchestrator/topology/OrchestratorTopology.java` | `com.itland.<service>` root package; constants injected with `@Value("${orchestrator.topics.x}")` under a service-named config prefix |
| Avro | `ssengine/src/main/avro/*.avsc`, `pom.xml` avro-maven-plugin | one `.avsc` per record (or an array file for related records), `stringType=String`, namespace `com.itland.orchestrator.avro` |
| Kafka client | `ssengine/.../producer/ActionBatchProducer.java` | native `KafkaProducer`, `acks=all`, `enable.idempotence=true`, routing headers `intendedFor`/`type` |
| Keys | `OrchestratorTopology.java` | `teamId + "\|" + repoId` string key |
| Flyway / tests | `KafkaToolsUI/backend` | `src/main/resources/db/migration/V1__init.sql`; unit tests `*Test`, Testcontainers ITs `*IT` run by failsafe on `mvn verify` |
| Errors (REST) | `KafkaToolsUI/backend/.../api/GlobalExceptionHandler.java` + `ApiError` record | `@RestControllerAdvice` mapping to a structured error record |

No existing pattern exists for: security, OPA, outbox/inbox, structured logging. Those are new.

---

## 1. Requirements restatement

Build a third service, **Governance**, as the only public REST entry point. It authenticates callers (OAuth2 JWT),
validates complete desired-state manifests, evaluates them with OPA (Stage 1), durably accepts them and publishes
`SUBMIT_MANIFEST` on `requests.manifest`. The Orchestrator computes the diff and publishes `PLAN_PROPOSED` on
`response.manifest`; Governance evaluates the full plan with OPA (Stage 2) and publishes `AUTHORIZE_PLAN` or
`REJECT_PLAN`, or parks the request in a durable human-approval workflow. Only authorized plans reach the Processor.
Final results come back on `response.manifest` and are exposed via REST. Policies are Rego v1 bundles, versioned,
activatable and rollback-able; policy *configuration* lives in PostgreSQL and is passed to OPA as trusted data.
Scoped, expiring exceptions are resolved server-side. Everything is audited, idempotent, fail-closed, and observable.

---

## 2. Key design decisions (assumptions you should confirm)

1. **Repository layout.** `policy_kafka/` becomes a Maven multi-module platform repo:
   `contracts/` (shared Avro), `orchestrator/` (ssengine **copied** in, package `com.itland.orchestrator` kept),
   `governance/` (new), `processor-simulator/` (test stand-in, see 4), `policies/` (Rego bundle), `deploy/` (compose, OPA config, ACL script), `docs/`.
   The original `kafka/ssengine` folder is left untouched.
2. **Spring Boot 4.1.2 for all modules**, including the Orchestrator (it only uses `spring-boot-starter`; the bump is low risk and lets one parent POM manage everything). Confluent 8.0.0 clients, Avro 1.11.3 (as today).
3. **Persistence = JDBC, not R2DBC.** Flyway needs JDBC anyway. Governance uses `JdbcClient` with explicit SQL and Java records, `TransactionTemplate`, and runs all DB and Kafka work on a dedicated bounded scheduler (`Schedulers.fromExecutor`) so Reactor event-loop threads never block. No JPA.
4. **Processor stand-in.** Because no Processor source exists, a small `processor-simulator` (native consumer/producer, consumes `teams.changes` ACTIONs, emits `ProcStatus` to `request.status`) is added for compose and end-to-end tests. It is explicitly not production code. Real-Processor compatibility is preserved because the `Action` schema and `teams.changes` contract are not changed.
5. **Manifest schema.** The REST manifest is the brief's shape (`teamId, repositoryId, environment, resources.topics[], resources.rbac[]`) and maps 1:1 onto an **extended** `RepoDesired` Avro record (add `environment`, `topics[TopicSpec]`, `identityPools[PoolSpec]`, all with defaults). This change is strictly necessary: the differ already requires those fields.
6. **Policy bundle distribution.** Governance serves the *active* bundle on an internal, token-protected endpoint (`/internal/opa/bundles/governance`), and OPA is configured with a bundle service polling it. Bundle versions (tarball + `.manifest.revision` + sha256 + `opa test` report) are stored in PostgreSQL. Activation / rollback = flipping the active row. Revision verification uses `?provenance=true` on the Data API; mismatch ⇒ `ERROR` (fail closed, bounded retry).
7. **No arbitrary Rego editing API.** Bundles are built and tested in CI/locally (`policies/build.sh` using the OPA Docker image), then uploaded as a version by an admin; only validated versions can be activated.
8. **Authorization model from JWT claims** (names configurable): `sub`, `teams[]`, `environments[]` (or `team_envs` map), `roles[]` with roles `governance-admin`, `governance-approver`, `governance-auditor`. One `CallerContext` record built once per request by `infrastructure/security`.

---

## 3. Kafka contract (exactly two topics)

Both topics keyed by **`teamId`** (string), RecordNameStrategy, Avro SpecificRecord, namespace `com.itland.platform.avro`.

```
ManifestCommand {
  commandId, commandType: SUBMIT_MANIFEST|AUTHORIZE_PLAN|REJECT_PLAN,
  requestId, teamId, repositoryId, environment,
  planId?, planDigest?, decisionId?, occurredAt (timestamp-millis),
  payload: union { SubmitManifestPayload{manifest: RepoDesired, manifestDigest},
                   AuthorizePlanPayload{stateVersion, authorizationExpiresAt},
                   RejectPlanPayload{reasonCode, violations[PolicyViolation]} }
}
ManifestEvent {
  eventId, eventType: PLAN_PROPOSED|MANIFEST_COMPLETED|MANIFEST_FAILED|MANIFEST_REJECTED,
  requestId, teamId, repositoryId, environment,
  planId?, planDigest?, decisionId?, stateVersion?, occurredAt,
  payload: union { PlanProposedPayload{actions[PlannedAction], manifestDigest},
                   ManifestCompletedPayload{results[ActionResult]},
                   ManifestFailedPayload{reasonCode, message, results[ActionResult]},
                   ManifestRejectedPayload{reasonCode, message} }
}
PlannedAction { actionId, resourceType, resourceName, actionType, payload map }
```

Headers: `requestId`, `planId`, `eventType`/`commandType`. `planDigest` = SHA-256 hex of the canonical JSON of the sorted action list + manifestDigest + stateVersion. `stateVersion` = new `long` field on `RepoConfirmed` (default 0) incremented by the Orchestrator on every reconcile.

Existing schemas: `RepoDesired` extended (defaults), `RepoConfirmed` + `stateVersion` (default 0), `EndOfBatch` + `requestId`, `planId`, `teamId`, `repoId` (defaults). `Action`, `ProcStatus` unchanged (`BatchState` gains `requestId`/`planId` defaults, internal only). All changes are backward compatible.

**Routing/correlation design (explicit).** Governance keys by `teamId` (preserved). Inside the Orchestrator both `SUBMIT_MANIFEST` and `AUTHORIZE_PLAN`/`REJECT_PLAN` are re-keyed to `teamId|repoId` through one repartition and handled by **one Processor-API node** that owns the `pending-plans` store (keyed by planId) and is connected to `confirmed-manifests`. Because both commands for a request go through the same key, they land on the same task, so the plan written on SUBMIT is visible on AUTHORIZE. The topology relies on Streams' store-connectivity co-location, not on an assumption about teamId partitioning; `requests.manifest` and `teams.repo.manifest` must have the same partition count (documented + asserted at startup).

---

## 4. Request lifecycle (Governance)

```
RECEIVED → STAGE1_DENIED | PENDING_APPROVAL_STAGE1 | SUBMITTED
SUBMITTED → PLAN_PROPOSED
PLAN_PROPOSED → PLAN_AUTHORIZED | PLAN_REJECTED | PENDING_APPROVAL_STAGE2 | EVALUATION_ERROR
PENDING_APPROVAL_* → PLAN_AUTHORIZED | PLAN_REJECTED | APPROVAL_EXPIRED | STALE_PLAN
PLAN_AUTHORIZED → COMPLETED | FAILED | REJECTED_BY_ORCHESTRATOR
```
Transitions are enforced by a pure `RequestState.transition(event)` function; illegal or late events (e.g. a replayed `PLAN_PROPOSED` after `COMPLETED`) are recorded in the inbox as `IGNORED_OUT_OF_ORDER` and never mutate state. Optimistic `version` column on `manifest_request`.

---

## 5. Files to create / change

| Path | Action | Why |
|---|---|---|
| `pom.xml` | CREATE | Root aggregator; parent Boot 4.1.2; manages confluent/avro/testcontainers/springdoc versions |
| `contracts/pom.xml`, `contracts/src/main/avro/**` | CREATE | Shared Avro: existing 5 schemas moved + extended; new `ManifestCommand`, `ManifestEvent`, payload records |
| `orchestrator/**` | CREATE (copy of ssengine) + MODIFY | See §7 for the exact modifications |
| `governance/pom.xml` | CREATE | webflux, webclient, security + oauth2-resource-server, jdbc, flyway, postgresql, actuator, micrometer, kafka-clients, kafka-avro-serializer, validation, springdoc-openapi-webflux, testcontainers (postgresql, kafka, junit-jupiter) |
| `governance/src/main/java/com/itland/governance/GovernanceApplication.java` | CREATE | Entry point |
| `.../api/manifest/ManifestController.java`, `ManifestRequestDto`, `ManifestAcceptedResponse`, `PolicyViolationResponse` | CREATE | `POST /api/v1/manifests` (202 / 422 with violations / 409 idempotency conflict) |
| `.../api/requests/RequestController.java` | CREATE | `GET /requests/{id}`, `/decisions`, `/plan` with team-scoped access |
| `.../api/approvals/ApprovalController.java` | CREATE | `GET /approvals`, `GET /approvals/{id}`, `POST /approvals/{id}/decisions` |
| `.../api/administration/{PolicyConfigurationAdminController, PolicyBundleAdminController, ExceptionAdminController, ApprovalConfigurationAdminController, AuditController}.java` | CREATE | Admin APIs, `governance-admin` role (`auditor` for audit reads) |
| `.../api/ApiExceptionHandler.java`, `ApiError.java` | CREATE | Structured errors (mirror KafkaToolsUI handler) |
| `.../application/submission/ManifestSubmissionService.java` | CREATE | Stage 1 orchestration: validate → config snapshot → exceptions → OPA → decision → persist request + outbox in one tx |
| `.../application/evaluation/{PolicyEvaluationService, OpaInputAssembler, DecisionAggregator}.java` | CREATE | Builds trusted OPA input, deterministic aggregation (hard-deny > deny > require-approval > allow; exemptions only for bypassable policies) |
| `.../application/exceptions/ExceptionResolver.java`, `ExceptionAdminService.java` | CREATE | Resolve valid scoped exceptions; create/revoke with audit |
| `.../application/approvals/{ApprovalService, ApprovalReleaseService, ApprovalExpiryJob}.java` | CREATE | Quorum, separation of duties, idempotent decisions, expiry; on quorum: revalidate policies + freshness then enqueue `AUTHORIZE_PLAN` |
| `.../application/tracking/{RequestTrackingService, RequestLifecycle}.java` | CREATE | State machine + queries |
| `.../application/policy/{PolicyConfigurationService, PolicyBundleService}.java` | CREATE | Config versions (draft/activate/rollback), bundle versions (upload/validate/activate/rollback), revision diagnostics |
| `.../domain/**` | CREATE | Records/enums: `Manifest`, `TopicSpec`, `RbacSpec`, `GovernanceStage`, `DecisionType`, `GovernanceDecision`, `PolicyViolation`, `PolicyException`, `ApprovalRequest`, `ApprovalStatus`, `RequestStatus`, `PlanDigest`, `ManifestDigest` |
| `.../infrastructure/opa/{OpaClient, OpaProperties, OpaHealthIndicator, OpaDecisionResponse}.java` | CREATE | WebClient, timeouts, bounded retry (transient only), provenance/revision check, fail-closed |
| `.../infrastructure/kafka/{KafkaClientFactory, ResponseManifestConsumer, OutboxRelay, InboxRepository, OutboxRepository, DeadLetterRecorder, ManifestCommandFactory}.java` | CREATE | Native consumer loop on its own thread, manual commit after inbox+state tx; outbox relay with idempotent producer; poison messages → `kafka_dead_letter` table + metric + log (no extra topic) |
| `.../infrastructure/persistence/*Repository.java` | CREATE | `JdbcClient` repositories, one per aggregate |
| `.../infrastructure/security/{SecurityConfig, CallerContext, CallerContextResolver, TeamAuthorization}.java` | CREATE | JWT resource server, claim mapping, team/env checks, role checks |
| `.../infrastructure/observability/{CorrelationFilter, GovernanceMetrics}.java` | CREATE | MDC requestId/planId, timers/counters/gauges, Kafka client metrics binding |
| `.../configuration/{BlockingExecutionConfig, KafkaProperties, GovernanceProperties, OpenApiConfig}.java` | CREATE | Dedicated scheduler, typed properties |
| `governance/src/main/resources/application.yml`, `application-local.yml` | CREATE | Externalized config, structured logging (`logging.structured.format.console=ecs`), actuator exposure |
| `governance/src/main/resources/db/migration/V1__governance_schema.sql` … `V4__outbox_inbox.sql` | CREATE | Tables listed in §6 |
| `governance/src/test/java/**` | CREATE | Unit tests (`*Test`): aggregator, state machine, digests, exception resolver, approval quorum/SoD, input assembler. ITs (`*IT`): REST+security with mock JWT, OPA container, Postgres, Kafka+SR, full E2E |
| `policies/governance/{naming,rbac,limits,destructive,approval,decision,exceptions}.rego` + `*_test.rego` + `.manifest` | CREATE | Rego v1 policies + tests |
| `policies/build.sh`, `policies/test.sh` | CREATE | `opa test --v1-compatible` and `opa build` via the OPA Docker image |
| `processor-simulator/**` | CREATE | Test stand-in (see decision 4) |
| `deploy/docker-compose.yml`, `deploy/opa/config.yaml`, `deploy/kafka/create-topics.sh`, `deploy/kafka/acls.sh`, `deploy/postgres/init.sql`, `deploy/mock-oidc/` | CREATE | Local env: Kafka (KRaft) + Schema Registry 8.0.0, Postgres 16, OPA 1.22, mock OIDC issuer, governance, orchestrator, processor-simulator; ACLs: only `governance` principal may write `requests.manifest`, only `orchestrator` may write `response.manifest` |
| `docs/{repository-analysis.md, kafka-contract.md, orchestrator-changes.md, adr/*.md, runbook.md}` | CREATE | Analysis, contract, explicit Orchestrator change list, ADRs for decisions in §2 |

---

## 6. Database tables (Flyway)

`manifest_request` (optimistic `version`, `idempotency_key` unique per principal, `manifest jsonb`, `manifest_digest`, `status`, `plan_id`, `plan_digest`, `state_version`),
`request_status_history`, `governance_decision` (stage, type, `policy_revision`, `configuration_version`, `matched_policies jsonb`, `violations jsonb`, `applied_exceptions jsonb`, digest, `opa_latency_ms`, error),
`proposed_plan` (plan_id, request_id, digest, state_version, `actions jsonb`),
`policy_configuration` (version, `content jsonb`, status DRAFT/ACTIVE/RETIRED), `policy_bundle_version` (revision, `bundle bytea`, sha256, `manifest jsonb`, `test_report jsonb`, status),
`policy_exception` (principal/team/env/resource_pattern/action_types/expires_at/reason/revoked_*),
`approval_configuration`, `approval_request` (plan_digest, required_approvals, approver_roles, expires_at, `version`), `approval_decision` (unique approval_id+approver_id),
`audit_record`, `kafka_inbox` (unique topic+partition+offset and `event_id`), `kafka_outbox` (status, attempts, next_attempt_at), `kafka_dead_letter`.

---

## 7. Orchestrator modifications (explicit list, "only where required")

1. **Compile/runtime fixes that block everything** (strictly necessary): extend `RepoDesired`; configure `SpecificAvroSerde` with `schema.registry.url` (+ `value.subject.name.strategy=RecordNameStrategy` for the two new topics only); read END_OF_BATCH with the `EndOfBatch` serde; call `diffAndActions` once per message; move Avro into `contracts`.
2. **Disable direct ingestion of `<team>.repo.manifest`** behind `orchestrator.legacyDirectIngest=false` (default false). This is the governance bypass.
3. **New source**: `requests.manifest` → re-key `teamId|repoId` → `GovernanceHandshakeProcessor` (Processor API) with stores `pending-plans` (planId → `PendingPlan{plan, digest, stateVersion, status PROPOSED|RELEASED|REJECTED, decisionId}`) and `confirmed-manifests`:
   - `SUBMIT_MANIFEST`: validate, diff against confirmed, build immutable plan (planId = UUID, digest), store, emit `PLAN_PROPOSED` to `response.manifest` (key teamId). Duplicate requestId with existing plan ⇒ re-emit same `PLAN_PROPOSED` (idempotent).
   - `AUTHORIZE_PLAN`: verify planId exists, digest matches, decisionId present, `stateVersion` == current confirmed `stateVersion`, not expired; mark `RELEASED` **before** forwarding; forward `Action`s + `EndOfBatch` (now carrying requestId/planId) to `teams.changes`. Already `RELEASED` ⇒ no-op (duplicate protection). Mismatch ⇒ `MANIFEST_REJECTED(STALE_PLAN|DIGEST_MISMATCH)`.
   - `REJECT_PLAN`: mark `REJECTED`, drop plan.
4. **Completion**: `BatchState` carries requestId/planId; when `totalCount == expected`, reconcile (increment `stateVersion`) and emit `MANIFEST_COMPLETED` (all success) or `MANIFEST_FAILED` (partial) to `response.manifest`. Wire the dangling feedback stream `.to(<team>.status)`.
5. Startup assertion that `requests.manifest` and `teams.repo.manifest` partition counts match; deserialization exception handler = log + continue + metric; Boot 4 parent; actuator + structured logging (same settings as Governance).

`teams.changes`, `request.status`, `Action`, `ProcStatus` contracts are unchanged ⇒ Processor untouched.

---

## 8. Rego v1 policies (`policies/governance`)

| File | Policies (policy ids) | Bypassable |
|---|---|---|
| `naming.rego` | `TOPIC_NAME_PATTERN`, `TEAM_PREFIX_REQUIRED`, `ENV_NAME_RESTRICTION` | yes |
| `rbac.rego` | `RBAC_ROLE_ALLOWED`, `RBAC_ENV_RESTRICTED`, `RBAC_TEAM_ROLE_ALLOWED`, **`RBAC_ADMIN_ROLE_FORBIDDEN` (hard deny)** | no (hard) |
| `limits.rego` | `MAX_TOPICS_PER_MANIFEST`, `MAX_PARTITIONS_PER_TOPIC`, `MAX_PARTITIONS_PER_PLAN`, `TOPIC_RETENTION_LIMIT` | yes |
| `destructive.rego` | `TOPIC_DELETE_RESTRICTED`, `RBAC_REVOKE_RESTRICTED`, `IDENTITY_POOL_DELETE_RESTRICTED` | yes |
| `approval.rego` | `PROD_TOPIC_DELETE_REQUIRES_APPROVAL`, `HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL`, `SENSITIVE_PERMISSION_REQUIRES_APPROVAL` | n/a |
| `exceptions.rego` | exception matching (principal, team, env, resource glob, action type, expiry) | — |
| `decision.rego` | `decision := {type, violations, matched, applied_exceptions, revision}` with deterministic ordering | — |

Input: `{stage, principal, team, environment, manifest, actions, plan{planId, digest, stateVersion}, request{requestId, manifestDigest}, configuration, exceptions}`. Configuration and exceptions are always injected by Governance, never by clients. Every policy has a `_test.rego`.

---

## 9. Tasks (phases, each ends green)

### Phase 0 — Bootstrap (Small)
- Root POM, `contracts`, copy ssengine → `orchestrator`, apply item 7.1 so it compiles. `docs/repository-analysis.md`.
- **Validate**: `mvn -q -pl contracts,orchestrator verify`

### Phase 1 — Contracts (Small)
- New Avro records; schema-compat unit test (old `RepoConfirmed` readable with new schema); digest utilities + tests.
- **Validate**: `mvn -q -pl contracts verify`

### Phase 2 — Governance skeleton (Medium)
- Module, Flyway migrations, `JdbcClient` repositories, security config + `CallerContext`, blocking scheduler, actuator, structured logging, OPA client with health indicator and revision check.
- **Validate**: `mvn -q -pl governance verify` with `SecurityConfigIT` (mock JWT) and `OpaClientIT` (OPA container, fail-closed on 5xx/timeouts)

### Phase 3 — Policies (Medium)
- Rego files + tests + bundle build; OPA compose config; seed `policy_configuration` V1 data.
- **Validate**: `policies/test.sh` (docker `opa test --v1-compatible`), `policies/build.sh`

### Phase 4 — Stage 1 + outbox + REST (Medium)
- Submission service, aggregator, exception resolver, idempotency, outbox relay, `POST /api/v1/manifests`, OpenAPI.
- **Validate**: `ManifestSubmissionIT` (202, 422 with violations, 409 idempotency, outbox → Kafka record with RecordNameStrategy subject)

### Phase 5 — Stage 2 + lifecycle + tracking (Medium)
- Inbox consumer, state machine, `PLAN_PROPOSED` evaluation, `AUTHORIZE_PLAN`/`REJECT_PLAN`, terminal events, tracking endpoints, dead-letter table.
- **Validate**: `Stage2EvaluationIT`, `RequestLifecycleTest` (duplicate/out-of-order/replayed events)

### Phase 6 — Approvals (Medium)
- Approval creation (both stages), quorum/SoD/expiry, idempotent decisions with optimistic lock, release revalidation (policy + exceptions + freshness), expiry job, APIs.
- **Validate**: `ApprovalServiceTest`, `ApprovalFlowIT`

### Phase 7 — Administration (Medium)
- Policy config versions, bundle versions + internal bundle endpoint, exceptions admin, approval config, audit history; all audited.
- **Validate**: `AdminApiIT` (role enforcement, activation/rollback changes served bundle and OPA revision)

### Phase 8 — Orchestrator handshake (Large)
- Items 7.2–7.5; `TopologyTestDriver` tests: propose, authorize (idempotent), stale plan, reject, completion.
- **Validate**: `mvn -q -pl orchestrator verify`

### Phase 9 — Local env + E2E (Medium)
- `processor-simulator`, compose, topic + ACL scripts, mock OIDC, `EndToEndIT` (Testcontainers: Kafka, SR, Postgres, OPA, orchestrator + simulator in-process).
- **Validate**: `mvn -q verify`; `docker compose -f deploy/docker-compose.yml up -d` + curl happy path and denied path

### Phase 10 — Observability + docs (Small)
- Metrics names, Kafka client metrics, runbook, ADRs, `orchestrator-changes.md`, README.
- **Validate**: `curl :8080/actuator/health/readiness`, `/actuator/prometheus | grep governance_`

---

## 10. Validation

```bash
mvn -q verify                                   # unit + Testcontainers ITs (Docker required)
policies/test.sh                                # opa test --v1-compatible via docker image
docker compose -f deploy/docker-compose.yml up -d
curl -s -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: k1" -d @docs/examples/manifest.json \
     -H 'Content-Type: application/json' localhost:8080/api/v1/manifests
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests/<id>
```

---

## 11. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Spring Boot 4 (Jackson 3, Security 7, split starters) friction with Confluent serializers (Jackson 2) | Medium | Confluent libs keep their own Jackson 2 on the classpath; WebFlux uses Jackson 3; pin versions in root POM; verify in Phase 2 before building on it |
| Orchestrator prototype never compiled/ran; fixing it is a prerequisite | High | Phase 0 makes it compile; `TopologyTestDriver` tests in Phase 8 |
| Kafka Streams co-partitioning for the handshake processor | Medium | Single repartition, one Processor node connected to both stores, startup partition-count assertion, driver tests |
| Processor absent: real compatibility unverified | High | Contracts to Processor unchanged; simulator honours documented `Action`/`ProcStatus` schemas; flagged in docs |
| OPA not installed locally | Low | All OPA tooling via `openpolicyagent/opa:1.22` image (Docker present) |
| Bundle revision verification semantics (`provenance=true`) | Low | Covered by `OpaClientIT` against the real image |
| Scope size | High | Phased delivery, each phase green before the next |

## 12. Acceptance
- [ ] All phases complete, `mvn -q verify` green, `policies/test.sh` green
- [ ] Compose stack runs: submit → PLAN_PROPOSED → AUTHORIZE_PLAN → simulated execution → COMPLETED visible via REST
- [ ] Denied manifest returns 422 with violations; hard deny cannot be exempted or approved around
- [ ] Duplicate `AUTHORIZE_PLAN` and replayed `PLAN_PROPOSED` cause no duplicate execution or state corruption
- [ ] OPA down ⇒ `ERROR`, nothing published, request retried within bounds
- [ ] Orchestrator changes limited to the list in §7; Processor contract unchanged
