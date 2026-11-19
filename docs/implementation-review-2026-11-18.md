# policy_kafka implementation review (2026-11-18, rbacs/ excluded)

Read-only review. Nothing in the repository was modified. Findings marked CONFIRMED were verified by reading the code path end to end or by running it; SUSPECTED were reported by a reviewer and are plausible but not re-verified.

## 1. Verified build and test state

| Suite | Result |
|---|---|
| Governance unit tests (surefire) | 48 passed |
| Governance integration tests (failsafe: e2e, release mode, approvals, shadow) | 52 passed |
| Contracts tests | 6 passed |
| Rego: `policies/test.sh`, `build.sh`, `scripts/validate.sh` | 79 tests, 88.7% coverage, 8 fixtures, schemas, Regal, gitleaks all OK |
| Admin UI lint, tsc, build | OK |
| Admin UI tests | 132 passed, 3 failed |
| Helm render-check | not run (helm / kubeconform not installed locally) |

Admin UI failures: `src/pages/exceptions/ExceptionsPage.test.tsx` (2) and `ExceptionDetailPage.test.tsx` (1). Cause: fixture `expiresAt: 2026-11-20` in `src/test/fixtures.ts:101` is now within the 7-day "Expiring soon" window of `lifecycle.ts`, and the page tests use the real clock. Not a product bug; the CI admin-ui job is red from today until fixed (use `vi.setSystemTime` or relative dates).

Important caveat on the Java result: the 52 ITs ran locally only because the private `orchestrator-simulator/` exists on this machine. `governance/pom.xml:185-206` activates the `without-simulators` profile when it is absent and excludes `e2e/**` and `support/PlatformContainers.java`, which is always the case in GitHub Actions.

## 2. What is implemented

- **Governance service** (Spring Boot 4.1.2, WebFlux + JdbcClient, Flyway V1-V7). `POST /api/v1/manifests` with Idempotency-Key, two-stage OPA evaluation (REQUEST, ACTION_PLAN), request state machine with optimistic versioning and status history, transactional outbox to `requests.manifest`, idempotent inbox from `response.manifest`, confirmed-state consumer from `teams.repo.manifest`, quota reservations under a team row lock, scoped exceptions, shadow findings, audit, dead letters, Micrometer metrics, readiness indicators for OPA, policy release and workflow engine.
- **Admin API**: team configuration (system of record, external-source guard), policy configuration versions with enforce/shadow per policy, six policy bundles with upload/activate/rollback in local mode, releases (one version per bundle), catalogue relay, exceptions, shadow findings, approval configurations, audit and dead letters. Role model: auditor reads, admin writes, approver decides; OPA pulls bundles through a static bundle token on `/internal/opa/**`.
- **Release modes**: `local` (DB-backed, admin uploads) and `release` (release file from Artifactory, upload endpoints return 409, forced by the `openshift` profile). Decision-time verification: all six bundles loaded, `ready`, contract major, per-bundle revision provenance; any mismatch is ERROR (fail closed).
- **Approvals on Flowable 8.0.0**: three BPMN processes (shared decision sub-process, manifest approval, action-plan approval). Approver verdict, quorum, segregation of duties, revalidation against current policy, release command written in the approver's transaction, timer expiry, cancellation on terminal events, shadow mode mirroring a legacy Java engine. ADR 0011 claims are honoured.
- **Policies**: six Rego v1 bundles (core composes manifests, naming, quotas, resources, changes), 20 policy ids (16 bypassable, 1 hard deny, 3 approvals), catalogue, release id algorithm shared with Java, signed `-O1` builds, Workflows A-E (validate, publish, promote, verify, rollback), Artifactory stand-in proofs, inventory page generator.
- **Admin UI**: React/Vite SPA, OIDC code+PKCE via oidc-client-ts in sessionStorage, same-origin nginx proxy for `/api` and `/oidc`, runtime `env.js` config, pages for overview, teams, shadow findings, exceptions, policy configurations, bundles/releases with activation watcher, approval rules, audit. Plan scope fully implemented; hand-maintained API types match the Java DTOs field for field.
- **Deploy**: Docker Compose (KRaft Kafka with SASL/PLAIN and least-privilege ACLs, Schema Registry, Postgres 16, mock OIDC, OPA 1.22.0 sidecar, governance, admin-ui, optional simulators). Helm charts for governance (OPA sidecar config rendered from `releases/<env>.json`, restricted-v2 security context, probes, NetworkPolicy, PDB, HPA, ServiceMonitor, ExternalSecrets) and admin-ui. CI: Maven verify, admin-ui, policies validate, gitleaks, CodeQL, image build + Trivy + SBOM, helm render-check; all actions SHA-pinned. Deploy workflow with helm --atomic, rollout status, post-deploy check, Workflow D dispatch.
- **Not implemented by decision**: Orchestrator and Processor (private stand-ins only); OPA programme Phases 6 and 7 (documented in docs/open-phases-6-7.md).

## 3. Findings

### HIGH (CONFIRMED)

1. **Response consumer loses records on a DB failure mid-batch.** `ResponseManifestConsumer.java:94-111`: a DataAccessException from `handle(record)` reaches the loop-level catch, which sleeps and polls again without seeking back; the next `commitSync` commits past the failed record. Same pattern in `RepositoryStateConsumer.java:92-109`. Scenario: short Postgres failover while a PLAN_PROPOSED arrives leaves the request in SUBMITTED forever.
2. **Inbox insert and handling are two transactions and a crash in between orphans the event.** `InboxProcessor.java:47-54` commits the row as `RETRY` with `next_attempt_at` NULL; `InboxRepository.claimRetries` requires it NOT NULL. Kafka redelivery is rejected as duplicate. Javadoc claims one transaction.
3. **Plan integrity reject path is unreachable.** `ManifestEventHandler.java:138-142` transitions SUBMITTED to PLAN_REJECTED, which `RequestLifecycle.java:15` forbids; the IllegalStateException marks the inbox entry DEAD and rolls back the REJECT_PLAN. Tampered or mis-hashed plans are neither rejected toward the Orchestrator nor recorded.
4. **All @Scheduled jobs share Spring's default single scheduler thread.** `GovernanceApplication.java` enables scheduling with no pool configured. `InboxRetryJob` re-runs stage 2 evaluations synchronously with 30 s blocks, starving `OutboxRelay`; AUTHORIZE_PLAN rows can be sent already past their 15 minute `authorizationExpiresAt`.
5. **Duplicate violation key crashes evaluation into ERROR.** `policies/bundles/core/core.rego:143-145` builds an object comprehension keyed `policyId|resourceType|resourceName`. Two violations with the same key and different message (e.g. two `forbiddenPatterns` matching one topic, or the admin-role hard deny firing on both the manifest binding and the matching RBAC_ASSIGN action) raise `eval_conflict_error`, OPA returns no result and Governance fails closed for the whole request. Reproduced with `opa eval`.
6. **Missing configuration keys make rules fail open.** `not x in h.config.<section>.<key>` is undefined when the key is missing, so the rule never fires: `destructive.rego:9,17,25`, `rbac.rego:9-10,20,30` (including the HARD_DENY in `authz.rego`), `naming.rego:9,17`. `PolicyConfigurationService.validate` checks only `limits.*`. Reproduced: deleting `configuration.destructive` turns a production TOPIC_DELETE from DENY to REQUIRE_APPROVAL. docs/bundle-authoring.md claims the opposite.
7. **Rendered Helm ConfigMap has a duplicate top-level `governance:` key in every environment.** `deploy/helm/governance/templates/configmap-governance.yaml:11-24` writes `governance.security.*` then appends `.Values.governance.config`, which all three `values-*.yaml` populate with `governance.workflow.engine`. Spring Boot's YAML loader rejects duplicate keys, so the pod fails at startup and `--atomic` rolls back every deploy. render-check cannot see it.
8. **CI and deploy workflows trigger on `main`; the repository branch is `master`.** `.github/workflows/ci.yml:9`, `deploy.yml:19`. Push CI and push-to-dev deploys never fire.
9. **Admin UI open redirect via protocol-relative `returnTo`.** `admin-ui/src/auth/guards.tsx:11` stores `location.pathname + location.search`; `LoginPage.tsx:18` and `CallbackPage.tsx:17` navigate to it. A path like `//evil.example/x` makes the router fall back to `window.location.assign`. Accept only `^\/(?!\/)`.

### MEDIUM

- CONFIRMED. CI never runs the e2e suite (profile `without-simulators` excludes `e2e/**`), while README and the CI job name claim it does.
- CONFIRMED. Stage-2 ERROR decisions are never persisted: `ManifestEventHandler.java:179-182` writes EVALUATION_ERROR then throws inside the transaction. Only `kafka_inbox.last_error` records it.
- CONFIRMED. Fail-closed fallback gaps: unexpected RuntimeException marks DEAD without `onExhausted` (`InboxProcessor.java:83-91`); `onExhausted` sends REJECT_PLAN but leaves the request in SUBMITTED with no history.
- CONFIRMED. Idempotency race recovery runs a query inside an aborted Postgres transaction (`ManifestSubmissionService.java:89-95`) and yields 500 instead of 200 replay. Same pattern in `ApprovalWorkflowService.java:110-113` for duplicate approval open.
- CONFIRMED. A second, different plan for an in-flight request is silently ignored without REJECT_PLAN (`ManifestEventHandler.java:124-135`).
- CONFIRMED. Repository tombstones on `teams.repo.manifest` never clear `repository_usage` (`RepositoryStateConsumer.java:120-124`); decommissioned repositories count against quotas and ownership forever, with no admin endpoint to clear them.
- CONFIRMED. Over-admission window: reservation released on MANIFEST_COMPLETED before the confirmed snapshot arrives on the other topic (`ManifestEventHandler.java:197`); freshness guard checks only the requesting repository.
- CONFIRMED. Exception action scope matches any action in the plan, not the violated one (`core/exceptions.rego:35-39`), and is ignored at REQUEST stage. Java does not validate `actionTypes` values or the glob.
- CONFIRMED. `workflow_dispatch` inputs interpolated with `${{ }}` directly into `run:` in `policies/.github/workflows/promote.yml`, `rollback.yml`, `verify.yml` (script injection with ARTIFACTORY_TOKEN and APP_REPO_TOKEN in scope). publish.yml does it correctly via env vars.
- CONFIRMED. Rollback hardcodes the promotion chain (`rollback.yml:94`, `promote.sh:43-45`); a release promoted candidates to production directly cannot be rolled back to.
- CONFIRMED. Actuator health details and Prometheus are unauthenticated and the Route forwards every path (`SecurityConfig.java:49`, `application.yml:120` show-details always).
- CONFIRMED. README quickstart fails on a public clone: `.env.example` sets `COMPOSE_PROFILES=simulators` and compose builds the git-ignored simulator directories.
- CONFIRMED. Helm chart undeployable with committed values: `networkPolicy.egress: []` with Egress policy type blocks Postgres, Kafka, IdP and Artifactory; `signing.publicKey` empty fails template; render-check masks both with `--set`.
- CONFIRMED. Admin controllers never use `@Valid` on request bodies, so `@NotBlank/@Min/@Max` on admin DTOs are decorative and the UI's field-error mapping never fires. `requiredApprovals: 0` is accepted via curl.
- CONFIRMED. Activation watcher reports a false "will be marked FAILED" for the whole release while the backend verifies per bundle (`admin-ui/src/pages/bundles/activation.ts:73-78`); release activation watches only the first changed bundle.
- CONFIRMED. 401 handling leaves the OIDC user in storage (`AuthProvider.tsx:96-100`); logout never calls `signoutRedirect`.
- SUSPECTED. Silent renew iframe is blocked by CSP with a real IdP (no `frame-src`); sessions expire mid-edit with no error logged.
- SUSPECTED. Revalidation before release sends empty principal roles (`ApprovalReleaseSteps.java:205`) and maps a new REQUIRE_APPROVAL to ALLOW (`:207-210`); latent until a rule reads roles or a new approval policy is added.
- SUSPECTED. Plan-level partition rules sum only TOPIC_CREATE (`quotas.rego:25-29`, `approval.rego:20-24`); a TOPIC_UPDATE raising partitions from 6 to 5000 passes.
- SUSPECTED. Each stage-2 evaluation holds the inbox connection plus team lock and needs a second pool connection (`PolicyEvaluationService.java:81-88`); with pool 16 a burst can stall on Hikari while holding the lock.

### LOW (selected)

- `X-Correlation-Id` never reaches MDC (`CorrelationWebFilter.java:27-31`).
- `failureReason` is overwritten by every transition reason (`ManifestRequestRepository.java:72`); COMPLETED requests show "Executed: 3 actions" as failure reason.
- Stage 2 evaluates with `principal.roles = []` (`ManifestEventHandler.java:155`).
- Per-bundle auto-rollback yields an untested 5-new + 1-old release composition (`PolicyBundleService.verifyActivation:216-246`).
- External-source team guard bypassable by omitting `source`; `delete` has no external check (`TeamConfigurationService.java:91-118`).
- Release file without `releaseId` accepted (`FilePolicyReleaseSource.parse:88`), contrary to ADR 0008.
- Unmapped IllegalStateException and DuplicateKeyException in admin paths return 500.
- `enforced()` treats any non-"enforce" value as non-blocking (`core.rego:80-81`).
- `app-repo-pr.sh:35` `git diff --quiet` is true for an untracked file; first promotion to a new environment opens no PR.
- Shadow-mode mirror failure can abort the authoritative legacy verdict (`ApprovalWorkflowService.java:176-181`).
- Deploy concurrency group ignores tag deploys; deploy-time Trivy scans only the governance image; secrets consumed as env with no pod reload on ESO refresh.
- Avro enums have no default symbol and envelope unions no fallback branch; compatibility test compares only against hand-typed legacy schemas.
- Admin UI: dead Swagger link in the shell, hard-coded `teams`/`environments` claim names, `['audit']` never invalidated after mutations, dead `releasesApi.current`/`bundlesApi.get`/`gen-api.sh` output.
- Dead code: `ManifestRequestRepository.findByStatus`, `RequestLifecycle.transition`, `CallerContext.system`, `OpaInputAssembler.zero`, `TeamConfiguration.stored`, `UsageSnapshot.empty`, `QuotaReservation.open`, `GovernanceProperties.Opa.releaseMode`, `GovernanceProperties.Workflow.defaultApprovalTtl`, `ShadowFindingRepository.count`.

## 4. Doc / code drift

- docs/governance.md lifecycle omits REJECTED_BY_ORCHESTRATOR and EVALUATION_ERROR edges, mentions a CANCELLED status that does not exist, and still lists `GET /internal/opa/bundles/governance`.
- docs/kafka-contract.md says digest mismatches are rejected; they are not (finding 3).
- Javadocs of ResponseManifestConsumer/InboxProcessor and UsageProjector claim single-transaction behaviour that is not the case.
- ADR 0008 (release id must equal computed), ADR 0009 (explicit source takeover), ADR 0010 (tombstones not addressed) are weaker in code.
- policies/README.md says 78 tests (79) and the release-id example is not in sorted order; `fixtures_test.rego` cites a policy_kafka path.
- docs/bundle-authoring.md "missing fields fail closed" is false (finding 6).
- docs/admin-ui.md: account menu does not show the token payload; CSP needs for a real IdP not documented.
- Plan files: Flowable clock bridge, job-metrics listener, `{outboxId, commandId}` return and delegate unit tests were planned but not built; "e2e runs under all three engine modes" is not wired in pom or CI.

## 5. Test coverage gaps (highest value first)

- Stage 2 failure paths: digest mismatch, unknown request, second plan in flight, OPA outage to RETRY/DEAD/REJECT_PLAN, inbox crash between insert and handle, consumer DB failure, outbox retry/FAILED.
- RepositoryStateConsumer: tombstone, out-of-order version, DB failure mid-batch. Reservation TTL sweep and the release/snapshot race.
- Authorization matrices: RequestController read roles, auditor on writes, approver on admin reads, bundle token wrong/missing, approver without environment access on `decide`.
- Rego: duplicate-key violations, missing config keys, unknown enforcement value, TOPIC_UPDATE partitions, TOPIC_DELETE_RESTRICTED positive case, limit boundaries, multi-action exception scope, catalogue superset check.
- Approvals: expiry racing a decision, cancel fallback when process not waiting, shadow failure isolation, dead-letter job recovery.
- Admin UI: LoginPage, CallbackPage, OIDC paths in AuthProvider (would have caught the open redirect), ActivationWatcher, MSW rollback error status mismatch (400 vs backend 409).
- Admin bodies: no controller test asserting bean validation.
