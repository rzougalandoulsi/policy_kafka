# Plan: Multi-bundle OPA governance, Artifactory distribution, OpenShift sidecar

**Source**: conversational `/ecc:plan` request (2026-11-01)
**Complexity**: Large (multi-phase programme; estimates per phase below)
**Status**: APPROVED 2026-11-01 ("proceed as planned"). **Phase 1 implemented** (see below). Phases 2 to 8 pending.

### Phase 1 delivery notes (2026-11-01)
- Done: `policies/bundles/{core,manifests,naming,quotas,resources,changes}` with roots `governance/<name>`, core composition (`decision`, `ready`, `policy_catalog`, `contract_version 1.0`), `policies/tests/` (53 tests incl. composition and catalogue), `policies/build.sh` producing six tarballs + `dist/release.json`, `scripts/check-roots.sh`, `scripts/check-lib-sync.sh`, `policies/test.sh`.
- Governance: `GovernanceProperties.Opa.bundles/decisionPath/catalogPath/readyPath`, `PolicyRelease`, per-bundle `policy_bundle_version.bundle_name` (V5), `policy_revisions` on decisions, `OpaClient.loadedRevisions/ready`, release verification in `PolicyEvaluationService` (ready + every revision), `PolicyReleaseReadinessIndicator` in the readiness group, `PolicyReleaseController` (`GET current`, `POST` atomic activation), per-bundle rollback, bundle endpoint per name; e2e IT grown to 18 scenarios (release activation with partial-set refusal, per-bundle rollback with `policyRevisions`, broken bundle fails closed + readiness DOWN until rollback).
- Deploy: `deploy/opa/config.yaml` six bundles; `deploy/scripts/upload-release.sh` replaces `upload-bundle.sh`. Docs: ADR 0007, governance.md, runbook, admin-ui.md, README, flow explainer.
- Deviations from the plan text: (1) Rego functions do not compile across bundles, so each bundle carries an identical `lib.rego` instead of importing `core.lib` (kept equal by `check-lib-sync.sh`). (2) Core's composed outputs are named `all_violations`, `all_approvals`, `policy_catalog` because `data.governance[ns].violations` would otherwise refer back to core and OPA rejects the composition as recursive. (3) Rollback is per bundle (`?bundle=`); release-level rollback is the Artifactory-mode Workflow E. (4) `verifyActivation` auto-rollback stays (per bundle) in local mode.


Legend used throughout: **[F]** verified in this repository (file path given), **[A]** assumption or information that lives outside this repository and must be confirmed, **[D]** design decision proposed here.

---

## 0. Executive summary

A large part of the requested target already exists in this repository: the Governance service evaluates every request twice through OPA, fails closed, tracks the bundle revision in every decision, manages scoped exceptions, approvals, an outbox/inbox Kafka protocol and an admin console. What is missing, in order of effort:

1. **Six independently versioned bundles** instead of today's single `governance` bundle, with a composition contract and a release manifest that defines a tested set of revisions.
2. **Team-level dynamic configuration** (multiple authorised prefixes, team quotas, role overrides) and a **trustworthy usage snapshot** for projected quota evaluation, with reservation-based concurrency control.
3. **Artifactory distribution, GitHub Actions (five policy workflows plus application CI/CD) and OpenShift sidecar deployment**: none of this exists yet (no `.github/`, no Helm or Kustomize, bundles are served by Governance itself).
4. **Resource-type coverage**: the Avro contracts and the Governance domain cover topics, identity pools and RBAC bindings only. Schemas, service accounts, connectors and Flink statements need additive contract changes that depend on the real Orchestrator and Processor.
5. **Rule migration from the real Orchestrator**: its source is not in this repository, so the migration matrix below is complete only for the code that is here, and a template plus required inputs are given for the rest.

---

## 1. Existing-code architecture assessment [F]

### 1.1 Modules
| Module | Role | Key facts |
|---|---|---|
| `contracts/` | Avro schemas, generated Java (`stringType=String`) | `ManifestCommand.avsc`, `ManifestEvent.avsc`, `GovernanceTypes.avsc` (commands `SUBMIT_MANIFEST`, `AUTHORIZE_PLAN`, `REJECT_PLAN`; events `PLAN_PROPOSED`, `MANIFEST_COMPLETED`, `MANIFEST_FAILED`, `MANIFEST_REJECTED`), `CommandPayloads.avsc`, `EventPayloads.avsc`, `RepoDesired.avsc` (`teamId, repoId, environment, topics[TopicSpec], identityPools[PoolSpec], rbacs[RbacSpec]`), `RepoConfirmed.avsc` (adds `stateVersion`, `environment`), `ManifestSpecs.avsc` (`TopicSpec{name, partitions?, config}`, `RbacSpec{principal, resource, role}`, `PoolSpec{name, principals[], rbacs[]}`), `Action.avsc`, `EndOfBatch.avsc`, `BatchState.avsc` |
| `governance/` | Spring Boot 4, WebFlux, Java 21, Maven | Layers `api/`, `application/`, `domain/`, `infrastructure/` under `com.itland.governance` |
| `policies/` | One Rego v1 bundle `governance` | Packages `governance` (entry `decision`), `governance.{naming,rbac,limits,destructive,approval,exceptions,helpers,catalog}`; 17 policy ids; 46 tests; built by `policies/build.sh` through `openpolicyagent/opa:1.22.0` in Docker; output `policies/dist/governance-bundle.tar.gz` |
| `admin-ui/` | React SPA behind nginx | Pages for exceptions, policy configurations, policy bundles (upload, activate, rollback, diagnostics), approval rules, audit; same-origin `/api` and `/oidc` proxies |
| `deploy/` | Docker Compose only | kafka (KRaft, SASL/PLAIN, ACLs in `deploy/kafka/acls.sh`), schema-registry, postgres, mock-oidc, opa (`deploy/opa/config.yaml` polls Governance's bundle endpoint with a static bearer), governance, admin-ui, two simulators. **No `.github/`, no Helm, no Kustomize, no OpenShift manifests.** |
| `orchestrator-simulator/`, `processor-simulator/` | Stand-ins | `OrchestratorLogic` verifies `decisionId`, `planId + planDigest`, authorization expiry, stale `stateVersion`, drops plans on `REJECT_PLAN`. The **real Orchestrator and Processor are not in this repository**; `docs/repository-analysis.md` records that the inspected prototype (`kafka/ssengine`) had **no validation rules** and no Processor source. |

### 1.2 Governance request path (classes)
| Concern | Class | Notes |
|---|---|---|
| REST entry | `api/manifest/ManifestController` | 202 / 200 replay / 422 denied / 409 key reuse / 503 fail closed |
| Technical validation (stays in Java) | `domain/manifest/ManifestValidator` | identifiers, environment pattern, topic name charset, partitions 1..10000, retentionMs ≥ -1, duplicates, blank principals |
| Stage 1 | `application/submission/ManifestSubmissionService` | idempotency, `RECEIVED` committed before OPA, decision + transition + outbox row in one transaction |
| OPA call | `infrastructure/opa/OpaClient` | WebClient, `POST {opa.url}/v1/data/governance/decision?provenance=true`, bounded retries, `OpaException` |
| Input | `application/evaluation/OpaInputAssembler` | `{stage, principal{id,roles}, team{id}, environment, manifest, actions[], plan{planId,planDigest,stateVersion}, request{requestId,manifestDigest}, configuration, exceptions[]}` |
| Decision | `application/evaluation/PolicyEvaluationService`, `DecisionAggregator` | revision check against ACTIVE bundle, Java aggregate must equal OPA `decision`, else `ERROR` |
| Lifecycle | `domain/request/RequestStatus`, `RequestLifecycle` | 14 states, 7 terminal |
| Stage 2 | `application/lifecycle/ManifestEventHandler` | recomputes `planDigest`, `ACTION_PLAN` evaluation, `AUTHORIZE_PLAN` / `REJECT_PLAN` / approval |
| Approvals | `application/approvals/ApprovalService`, `ApprovalReleaseService`, `ApprovalExpiryJob` | quorum, SoD, TTL, revalidation, `STALE_PLAN` |
| Kafka | `infrastructure/kafka/KafkaClientFactory` (Avro, `RecordNameStrategy`, idempotent producer, manual commits), `OutboxRelay`, `ResponseManifestConsumer`, `InboxProcessor`, `ManifestCommandPublisher` | key `teamId`; ACLs: only principal `governance` may write `requests.manifest` |
| Policy management | `application/policy/PolicyBundleService` (upload, activate, rollback, `verifyActivation` with 90 s grace and auto-rollback), `PolicyConfigurationService` (sections `naming, rbac, limits, destructive, approvals`), `api/administration/OpaBundleEndpoint` (`GET /internal/opa/bundles/{name}`, ETag, bearer) | bundle bytes stored in `policy_bundle_version` |
| Exceptions | `application/exceptions/ExceptionAdminService`, `ExceptionResolver` | resolved per (principal, team, environment, now); OPA matches resource glob and action scope (`exceptions.rego`) |
| Config surface | `configuration/GovernanceProperties.Opa` | `url, decisionPath, bundleName=governance, connectTimeout, responseTimeout, maxRetries, retryBackoff, verifyRevision, bundleToken` |
| Health | `infrastructure/opa/OpaHealthIndicator` | reports `opa` |
| Persistence | 15 tables (`V1`–`V3` Flyway) | `repository_state` holds only `(team, repo, env) → state_version`; **no resource usage, no team registry, no quota data** |
| Tests | 24 unit tests; `e2e/GovernanceEndToEndIT` with 17 ordered scenarios on Testcontainers (`support/PlatformContainers` rebuilds the bundle when Rego is newer) | |

### 1.3 What today's design already satisfies from the request
Two-stage enforcement, OPA Data API, fail closed, revision tracking per decision, scoped exceptions with hard-deny protection, approvals bound to digests, outbox/inbox idempotency, teamId routing, exactly two protocol topics, admin console, local bundle serving.

### 1.4 Gaps versus the request
| Requirement | Today | Gap |
|---|---|---|
| Six bundles, non-overlapping roots | one bundle, root `governance` | split, compose, release manifest |
| Multiple authorised prefixes per team | prefix = `teamId + separator` (`naming.rego`, `h.team_prefix`) | team configuration input |
| Team quotas across repositories, projected usage | per-manifest and per-plan limits only | usage snapshot, reservations |
| Schemas, service accounts, connectors, Flink | not in contracts, domain or Rego | additive contracts (external dependency) |
| Artifactory, promotion, signing | Governance serves bundles from Postgres | new distribution path, keep local path for dev |
| OpenShift sidecar, probes, SCC, NetworkPolicy | Compose only | Helm chart |
| GitHub Actions | none | 5 policy workflows + app CI/CD |
| Shadow mode | none | per-policy enforcement mode |
| Readiness on bundle set | health checks one revision | readiness indicator on the full set |

---

## 2. Target architecture [D]

```
                 GitHub: governance-policies            GitHub: policy_kafka (this repo)
                 ┌──────────────────────────┐           ┌──────────────────────────────┐
                 │ A validate  B build+sign  │           │ app CI: mvn verify, image,   │
                 │ C promote   D verify  E rb│           │ helm upgrade, verify, smoke  │
                 └────────────┬─────────────┘           └───────────────┬──────────────┘
                              │ immutable tar.gz + sha256 + sig          │ image tag = git sha
                              ▼                                          ▼
                 Artifactory  opa-bundles/<bundle>/<rev>/...   releases/<env>/<releaseId>.json
                              ▲ (read-only token, HTTPS)                 │ rendered into
                              │                                          ▼
   OpenShift Pod ─────────────┼────────────────────────────────────────────────────────────
   │  opa sidecar  --addr 127.0.0.1:8181  --diagnostic-addr :8282 (health/metrics only)   │
   │     bundles: core, manifests, naming, quotas, resources, changes  (pinned revisions)  │
   │  governance   OPA_URL=http://127.0.0.1:8181  release manifest mounted (ConfigMap)     │
   │     readiness = all 6 revisions == release manifest AND contract_version supported    │
   └──────────────────────────────────────────────────────────────────────────────────────
         │ REST (Route → Service :8080)            │ Kafka: requests.manifest / response.manifest
       clients, approvers, admin console          Orchestrator ──teams.changes──▶ Processor
                                                   ▲ teams.repo.manifest (confirmed state, read by Governance for usage)
```

Principles: OPA never talks to Kafka or to a database; Governance supplies all context as `input`; bundles carry logic and static reference data only; team data and exceptions live in Governance; the release manifest is the unit of promotion, not a bundle.

---

## 3. OPA bundle architecture [D]

### 3.1 Bundles, roots, packages
| Bundle | `.manifest` roots | Packages | Exposes (contract) |
|---|---|---|---|
| `governance-core` | `governance/core` | `governance.core` (entry `decision`, `ready`, `catalog`), `governance.core.lib` (helpers, severity constants, exception matching), `governance.core.authz` (team ownership, environment access, global prohibitions) | `decision`, `ready`, `catalog`, `contract_version` |
| `governance-manifests` | `governance/manifests` | `governance.manifests` | `violations`, `approvals`, `catalog`, `contract_version` |
| `governance-naming` | `governance/naming` | `governance.naming` (+ `governance.naming.topics`, `.schemas`, `.pools`, `.service_accounts`, `.connectors`, `.flink`) | same |
| `governance-quotas` | `governance/quotas` | `governance.quotas` | same |
| `governance-resources` | `governance/resources` | `governance.resources` (+ per type) | same |
| `governance-changes` | `governance/changes` | `governance.changes` (destructive, production, high impact, approvals, risk class, maintenance windows) | same |

Rules: every package lives under its bundle root (OPA rejects a bundle whose files leave its roots, so overlap is caught at build time and at load time). Each bundle has `data.json` with static reference data only (for example known Confluent role names, risk classes), never team data.

### 3.2 Dependency direction (no cycles)
`core.lib` is the only cross-bundle import target. The five domain bundles import `data.governance.core.lib`. Core composes the others **by dynamic reference** (`data.governance[ns]`), never by `import`, so there is no compile-time cycle and a missing bundle is observable instead of a compile error.

### 3.3 Composition into one decision (in `governance.core`)
```
required := {"manifests", "naming", "quotas", "resources", "changes"}
loaded(ns)  if object.get(data.governance, [ns, "contract_version"], null) != null
compatible(ns) if semver.compare(major(data.governance[ns].contract_version), major(contract_version)) == 0
ready if { every ns in required { loaded(ns); compatible(ns) } }

raw_violations := union({ data.governance[ns].violations | some ns in required })  ∪ authz.violations
# then exactly today's algorithm: annotate with exceptions, blocking = non-exempted,
# hard_denied suppresses approvals, outcome precedence HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW
decision := { "decision": outcome, "violations", "approvals", "matchedPolicies", "appliedExceptions",
              "ready": ready, "bundles": { ns: data.governance[ns].contract_version | ... }, "revision": revisions }
```
Governance treats `ready == false` exactly like an OPA error (fail closed). The provenance block already returned by OPA lists every loaded bundle's revision; Governance compares the full map to the expected release set (section 7).

### 3.4 Independent updates and compatibility
- `contract_version` (`MAJOR.MINOR`) is declared per bundle and by core. A major bump in core's input contract is rolled out as: new domain bundle revisions that accept both majors → new core → old domain revisions retired. Core refuses (`ready=false`) when majors differ, so an incomplete promotion fails closed rather than evaluating a mixed set.
- A **release manifest** (section 10) is the only thing promoted. Single-bundle hotfixes are still a new release manifest that changes one revision; Workflow A runs the whole test suite with all six bundles loaded (`opa test bundles/`), so cross-bundle breakage is caught before build.

### 3.5 Catalogue
Each bundle publishes its own `catalog` list (same shape as today's `catalog.rego`); core merges them. `GET /api/v1/admin/policies` (`PolicyCatalogController`) switches to `data.governance.core.catalog` and gains a `bundle` field per entry. The admin console's exception form keeps working unchanged apart from the extra column.

### 3.6 Mapping of today's 17 policies
| Today (`policies/governance`) | Target bundle |
|---|---|
| `naming.rego`: `TOPIC_NAME_PATTERN`, `TEAM_PREFIX_REQUIRED` (becomes `AUTHORIZED_PREFIX_REQUIRED` over `input.team.authorizedPrefixes`), `ENV_NAME_RESTRICTION` | naming |
| `rbac.rego`: `RBAC_ADMIN_ROLE_FORBIDDEN` (HARD_DENY) | core (global prohibition) |
| `rbac.rego`: `RBAC_ROLE_ALLOWED`, `RBAC_ENV_RESTRICTED`, `RBAC_TEAM_ROLE_ALLOWED` | resources |
| `limits.rego`: `MAX_TOPICS_PER_MANIFEST`, `MAX_PARTITIONS_PER_TOPIC`, `TOPIC_RETENTION_LIMIT` | resources (per-resource restrictions) |
| `limits.rego`: `MAX_PARTITIONS_PER_PLAN` | quotas (replaced by team-level projected quotas; kept as a plan guard) |
| `destructive.rego`: `TOPIC_DELETE_RESTRICTED`, `RBAC_REVOKE_RESTRICTED`, `IDENTITY_POOL_DELETE_RESTRICTED` | changes |
| `approval.rego`: `PROD_TOPIC_DELETE_REQUIRES_APPROVAL`, `HIGH_IMPACT_CHANGE_REQUIRES_APPROVAL`, `SENSITIVE_PERMISSION_REQUIRES_APPROVAL` | changes |
| `exceptions.rego`, `helpers.rego`, `decision.rego`, `catalog.rego` | core |
| new: repository ownership, cross-repository resource conflicts, supported resource declarations | manifests |
| new: team quotas per resource type, projected usage | quotas |

---

## 4. Java rule migration matrix

### 4.1 Verified rules in this repository [F]
| Rule | Source | Config source | Class | Target | Input needed | Dynamic | Exception | Tests |
|---|---|---|---|---|---|---|---|---|
| Identifier / environment patterns, resource presence, duplicate names, partitions 1..10000, retentionMs ≥ -1, config key/value present, blank principals | `ManifestValidator.validate` | constants | **A** stays in Java | none | no | no | existing unit tests stay |
| Idempotency key reuse with different digest → 409 | `ManifestSubmissionService.submit` | n/a | **A** | | | | `idempotencyKeyReplaysAndConflicts` IT |
| Plan digest recomputation, out-of-order events, unknown request | `ManifestEventHandler.onPlanProposed` | n/a | **A** | | | | `replayedAndOutOfOrderEventsDoNotCorruptState` IT |
| decisionId present, planId+digest match, authorization expiry, stale stateVersion | `orchestrator-simulator OrchestratorLogic` | n/a | **A** (must exist in the real Orchestrator too) | | | | simulator + IT scenario 5, 12 |
| Positive limits, required sections in a configuration | `PolicyConfigurationService.validate` | n/a | **A** | | | | existing unit tests |
| 14 governance violations, 3 approval requirements | `policies/governance/*.rego` | `policy_configuration` JSON | already **B**, re-homed per 3.6 | existing `input` | yes | per catalogue | 46 Rego tests move with their packages |

### 4.2 Rules named in the request but not inspectable here [A]
The real Orchestrator's startup-loaded naming conventions and allowed RBAC roles are not in this repository, and the inspected prototype had no validation rules. The matrix for them cannot be filled honestly without its source. Required input: read access to the Orchestrator repository (or an export of its validation classes and configuration files). Expected mapping once inspected:

| Orchestrator rule (expected) | Class | Target bundle / policy |
|---|---|---|
| Topic naming convention | **C** (keep charset check, move convention) | naming / `TOPIC_NAME_PATTERN`, `AUTHORIZED_PREFIX_REQUIRED` |
| Allowed RBAC roles | **B** | resources / `RBAC_ROLE_ALLOWED` |
| Structural manifest checks | **A** | stay |
| Diff correctness, state versioning | **A** | stay |

### 4.3 Phased migration (maps to request section 15)
1. Inventory (needs Orchestrator source). 2. Rego equivalents land in the six bundles with tests. 3. OPA integration exists; extend input (section 5). 4. **Shadow mode** = per-policy `enforcement: shadow` in the policy configuration (`policy_configuration.enforcement[policyId]`), plus a Governance-side `shadow_finding` table and metric `governance_shadow_findings_total{policyId}`; shadow violations are recorded in the decision and audit but never block. Orchestrator-side discrepancies surface as `MANIFEST_REJECTED` events after a Governance `ALLOW`, which Workflow D's smoke tests and a dashboard query over `request_status_history` report. 5–6. Compare and fix. 7. Flip stage 1 policies to `enforce`. 8. Flip stage 2 policies. 9. Remove the Orchestrator's duplicated business rules (its repository). 10. Technical validations stay in both services.

---

## 5. Policy input/output contract (v2.0) [D]

### 5.1 Input (superset of today's `OpaInputAssembler` output, additive)
```json
{
  "contractVersion": "2.0",
  "stage": "REQUEST | ACTION_PLAN",
  "principal": {"id": "...", "roles": ["..."], "teams": ["..."], "environments": ["..."]},
  "team": {"id": "team-a", "authorizedPrefixes": ["orders.", "payments."],
           "quotas": {"topics": 25, "topicPartitions": 100, "schemas": 50, "identityPools": 5,
                      "serviceAccounts": 10, "connectors": 5, "rbacBindings": 100, "flinkStatements": 10},
           "allowedRoles": ["..."], "environments": {"production": {"...": "..."}},
           "configurationVersion": 12, "source": "governance | external"},
  "repository": {"id": "repo-1", "ownedByTeam": true},
  "environment": "production",
  "manifest": {"topics": [...], "schemas": [...], "identityPools": [...], "serviceAccounts": [...],
               "connectors": [...], "rbac": [...], "flinkStatements": [...]},
  "actions": [{"actionId", "resourceType", "resourceName", "actionType", "payload"}],
  "plan": {"planId", "planDigest", "stateVersion"},
  "usage": {"current": {"topics": 18, "topicPartitions": 72, ...},
            "reserved": {"topicPartitions": 12, ...},
            "byRepository": {"repo-2": {"topics": 6, ...}},
            "asOfStateVersions": {"repo-1": 41, "repo-2": 17}, "snapshotAge": "PT3S"},
  "ownership": {"resources": {"TOPIC:orders.events": "repo-2"}},
  "request": {"requestId", "manifestDigest"},
  "configuration": {"naming": {...}, "rbac": {...}, "limits": {...}, "destructive": {...}, "approvals": {...},
                    "enforcement": {"POLICY_ID": "enforce | shadow"}},
  "exceptions": [{"id", "policyId", "principalId", "teamId", "environment", "resourcePattern", "actionTypes"}],
  "now": "2026-11-01T10:00:00Z"
}
```
`input.now` is supplied by Governance so maintenance-window rules are testable and deterministic. Unknown resource arrays are empty until the contracts carry them.

### 5.2 Output (unchanged shape, two additions)
`decision`, `violations[]` (`policyId, severity DENY|HARD_DENY, message, resourceType, resourceName, bypassable, exempted, exceptionId, enforcement`), `approvals[]`, `matchedPolicies`, `appliedExceptions`, plus `ready` and `bundles{ns: contractVersion}`. Provenance (`?provenance=true`) supplies per-bundle revisions.

### 5.3 Where the contract is enforced
- JSON Schema files `contracts/opa/input.schema.json` and `output.schema.json` in the policy repository; Workflow A validates every test fixture against them; Governance has a unit test that serialises `OpaInputAssembler` output and validates it against the same schema (the schema is copied into the app repo by a pinned version, see section 8).

---

## 6. Dynamic configuration and exception architecture [D]

### 6.1 Authoritative sources
| Value | Authoritative source | Mechanism |
|---|---|---|
| Global policy parameters (patterns, environment overrides, approval thresholds) | Governance `policy_configuration` [F] | unchanged, versioned, admin API and UI |
| Team identity, authorised prefixes, quotas, allowed-role overrides, environment parameters | **Governance `team_configuration`** (new, versioned, audited) as system of record **unless** an enterprise registry exists [A] | if an external registry exists: `source=external`, read-only in the UI, refreshed by `TeamRegistrySyncJob` with `synced_at`, `source_version`; drift reported, never silently overwritten. Ownership stated in the ADR. |
| Team membership of a principal | JWT claims `teams[]`, `environments[]` [F] | unchanged |
| Current resource usage per repository | **Orchestrator confirmed state** on `teams.repo.manifest` [F: topic exists, `RepoConfirmed` has `teamId, repoId, environment, stateVersion, topics, pools, rbacsKey`] | Governance consumes it (read ACL needed) into `repository_usage`; see 6.2 |
| Exceptions | Governance `policy_exception` [F] | unchanged |

No second copy of team data is created when an external registry is authoritative; the Governance table is then a cache with explicit ownership and freshness, surfaced in `GET /api/v1/admin/teams/{id}` as `source`, `syncedAt`.

### 6.2 Usage snapshot and projected quotas
- New consumer `RepositoryStateConsumer` on `teams.repo.manifest` (compacted, keyed `teamId|repoId`) upserts `repository_usage(team_id, repository_id, environment, state_version, counts jsonb, resources jsonb, updated_at)`. `resources` keeps the owned resource names for cross-repository conflict checks (manifests bundle).
- Stage 1: `usage.current` = sum over the team's other repositories plus this manifest's declared resources; the quota policy compares `current + manifest` to `team.quotas`.
- Stage 2: `usage.current` = sum over all repositories at their recorded `state_version`; `delta` is derived from `actions` (create topic +1 topic, +N partitions; delete −1, −N; partition increase +Δ); `usage.reserved` = open reservations of other in-flight authorised plans for the team and environment. Projected = current + reserved + delta.
- Freshness guard: if the snapshot for this repository is older than `plan.stateVersion` (Governance has not yet consumed the confirmed state the Orchestrator planned against), evaluation returns `ERROR` and the inbox retries (existing `RetryableException` path), so quotas are never computed on stale data.
- Concurrency: `quota_reservation(id, team_id, environment, request_id, plan_id, deltas jsonb, expires_at, released_at)`. `ManifestEventHandler` and `ApprovalReleaseService` take `SELECT ... FOR UPDATE` on `team_configuration` row (per team) inside the same transaction that writes `PLAN_AUTHORIZED` and the outbox row, re-check projected ≤ quota against committed reservations, then insert the reservation. Released on `MANIFEST_COMPLETED`, `MANIFEST_FAILED`, `MANIFEST_REJECTED`, `STALE_PLAN`, `APPROVAL_EXPIRED` and by TTL (`authorizationValidity` [F in `GovernanceProperties.Workflow`]). The row lock serialises two simultaneously approved plans of one team; OPA evaluates, Java enforces the final arithmetic under the lock (this is admission control, not policy logic, so it is acceptable in Java; the quota values and rule still come from OPA input and Rego).

### 6.3 Exceptions
Existing model already covers principal, policy, team, environment, resource glob, action types, expiry, revocation, reason and audit [F]. Additions: `bypassable` and `kind` come from the merged catalogue so the UI disables approval requirements and hard denies (approvals are never bypassable by an exception; `changes` bundle rules ignore `input.exceptions`). Exception resolution stays in `ExceptionResolver`.

---

## 7. Kafka integration changes

### 7.1 Protocol
No change to the two topics, the command and event enums, `teamId` keys, outbox and inbox [F]. `MANIFEST_REJECTED` stays (it is already in `GovernanceTypes.avsc`).

### 7.2 Additive schema changes (backward compatible, defaults) [D, depends on Orchestrator and Processor owners A]
- `ManifestSpecs.avsc`: new records `SchemaSpec{subject, format, schema?, compatibility?}`, `ServiceAccountSpec{name, description}`, `ConnectorSpec{name, class, config map}`, `FlinkStatementSpec{name, statement, computePool}`.
- `RepoDesired.avsc` and `RepoConfirmed.avsc`: arrays `schemas`, `serviceAccounts`, `connectors`, `flinkStatements`, default `[]`.
- `Action.avsc`: `resourceType` enum extension (additive symbols with default) if it is an enum; verify at implementation (only `type, payload` are documented in the analysis).
- `AuthorizePlanPayload` already carries authorization expiry [F in simulator]; add `policyReleaseId` (string, default "") so the Orchestrator can log which release authorised the plan.
- Compatibility: subjects use `RecordNameStrategy` [F], so each record is its own subject; set BACKWARD compatibility in the registry for all `com.itland.platform.avro.*` and `com.itland.orchestrator.avro.*` subjects and add a `contracts` Maven test that checks new schemas against the previous git revision with the Avro `SchemaCompatibility` API.

### 7.3 New consumption
Governance reads `teams.repo.manifest` (new ACL `Read, Describe` for principal `governance` in `deploy/kafka/acls.sh` and in the enterprise cluster [A]). It never writes to it.

### 7.4 Guarantees kept
Only `governance` may write `requests.manifest` (ACL) [F]; duplicate `AUTHORIZE_PLAN` is a no-op on the Orchestrator side (documented requirement, simulator does it) [F]; approvals are bound to `planDigest` [F]; authorisation carries an expiry [F].

---

## 8. Policy repository structure [D]

**Recommendation: a separate repository `governance-policies`.** Reasons: independent release cadence, protected environments for promotion that have nothing to do with application deploys, policy authors who never touch Java, and Workflow B and C permissions (Artifactory write) that the application repository should not hold. During the transition the layout is created inside this repository under `policies/` so the extraction is a `git subtree split`.

```
governance-policies/
  bundles/
    core/        .manifest  data.json  core.rego  lib.rego  authz.rego  decision.rego  catalog.rego  *_test.rego
    manifests/   .manifest  data.json  manifests.rego  catalog.rego  *_test.rego
    naming/      .manifest  data.json  naming.rego  topics.rego  schemas.rego  ...  catalog.rego  *_test.rego
    quotas/      ...
    resources/   ...
    changes/     ...
  contracts/     input.schema.json  output.schema.json  catalog.schema.json  release-manifest.schema.json  CHANGELOG.md
  tests/
    fixtures/    inputs/*.json (schema-validated)   expected/*.json
    integration/ opa-eval smoke scripts used by Workflow D
  scripts/       build.sh  changed-bundles.sh  check-roots.sh  release-manifest.sh  publish.sh  promote.sh  verify-active.sh
  .github/workflows/  validate.yml  publish.yml  promote.yml  verify.yml  rollback.yml
  docs/          bundle guide, contract, runbook
  OPA_VERSION    (pinned, e.g. 1.22.0)
```

**Contract between the two repositories**
| Owner | Responsibility |
|---|---|
| governance-policies | Rego, bundle roots, `contract_version`, catalogue schema, release manifests, Artifactory publishing and promotion |
| policy_kafka (Governance) | `OpaInputAssembler` producing a given input contract major, `PolicyReadinessIndicator`, decision parsing, exception and team data, the OPA sidecar config template and the release-manifest ConfigMap in the Helm chart |
| Shared | `contracts/*.schema.json` published as a versioned artifact (`opa-contracts/<version>/`); Governance pins the version in `pom.xml` properties and runs the schema test; a contract major bump requires a Governance release first (it must accept both majors), then policies |

---

## 9. GitHub Actions workflow specifications [D]

Conventions for all: pinned action SHAs, pinned `OPA_VERSION` from the repo file, `permissions:` least privilege, `concurrency` groups, no secrets in files, OIDC-first.

### Workflow A, `validate.yml` (pull_request on `bundles/**`, `contracts/**`, `tests/**`)
1. `scripts/changed-bundles.sh` from `git diff --name-only origin/main...HEAD`; always validate `core` plus changed bundles, and always run the full `opa test` because of cross-bundle references.
2. Install pinned OPA (checksum verified) or run the `openpolicyagent/opa` image.
3. `opa fmt --diff`, `opa check --strict --v1-compatible bundles/`.
4. `opa test --v1-compatible bundles/ --coverage` with a threshold (start at 85 %).
5. `scripts/check-roots.sh`: parse every `.manifest`, assert roots are exactly the six expected, pairwise non-prefix, and every `package` in a bundle is under its root.
6. Cross-bundle dependency check: `opa inspect` / grep imports; only `data.governance.core.lib` may be imported across bundles; core must not import other bundles.
7. Contract check: validate `tests/fixtures/inputs/*.json` against `input.schema.json`; `opa eval` each fixture through `data.governance.core.decision` with all bundles loaded and validate the output against `output.schema.json`; compare to `expected/*.json`.
8. Quality and security: Regal lint (pinned), `opa build` dry run per bundle (catches root violations), secret scan (gitleaks), forbid `http.send` and `opa.runtime` in policies.
9. Required status check on `main`; CODEOWNERS per bundle directory.

### Workflow B, `publish.yml` (push to `main` touching `bundles/**`, or `workflow_dispatch` with a bundle list)
1. Changed bundles since the last published tag (fallback: all).
2. Revision = `<bundle>-<yyyyMMdd>.<run_number>-<short sha>`; written into `.manifest.revision` and `.manifest.metadata{git_sha, git_ref, built_at, contract_version, opa_version}`.
3. `opa build -b bundles/<name> -O1 --v1-compatible -r <revision> --signing-key $KEY --signing-alg RS256 --claims-file claims.json -o dist/<name>-<revision>.tar.gz`, `sha256sum`.
4. Smoke: `opa run --server` locally with the built bundles, eval the fixtures, assert `ready == true`.
5. Authenticate to Artifactory with a short-lived access token obtained through the JFrog OIDC integration (`jfrog/setup-jfrog-cli` with `oidc-provider-name`) [A: the Artifactory instance must have an OIDC provider configured for the GitHub org]; fallback is an environment secret with a scoped, expiring token, never a file in the repo.
6. Upload with `--fail-fast` to `opa-bundles/<bundle>/<revision>/` (`.tar.gz`, `.sha256`, `.signatures.json` extracted for audit) plus properties `git.sha`, `contract.version`, `opa.version`. Pre-check `HEAD` of the path: if it exists, fail (immutability also enforced server-side, section 10).
7. Generate a **candidate release manifest** `releases/candidates/<releaseId>.json` listing all six current revisions (unchanged bundles keep their last published revision), with checksums and contract versions, and open it as a GitHub Release draft attachment.

### Workflow C, `promote.yml` (`workflow_dispatch` inputs: `releaseId`, `environment ∈ dev|staging|production`; `environment:` protected GitHub environments with required reviewers for staging and production)
1. Download the candidate or source-environment manifest; verify every listed artifact exists in Artifactory and its checksum and signature match (no rebuild).
2. Write `releases/<env>/<releaseId>.json` (immutable) and append to `releases/<env>/history.ndjson` (append-only by convention; Artifactory does not provide a mutable pointer, so the current selection is **not** a file in Artifactory).
3. Open a pull request in `policy_kafka` that updates `deploy/helm/governance/releases/<env>.json` (the ConfigMap source) to the release id and revisions; for `dev` the PR auto-merges. Merging triggers the application deploy workflow, which rolls the pods and runs Workflow D's verification. Promotion history = Git history of that file plus `history.ndjson`.

### Workflow D, `verify.yml` (called by the deploy workflow and nightly)
1. From the runner: `HEAD` every artifact URL of the environment's release with a read-only token (Artifactory reachable, artifacts present).
2. Against the deployed namespace: `oc exec` into one Governance pod and call `http://127.0.0.1:8282/health?bundles` on the sidecar and `GET /actuator/health/readiness` on Governance; call `GET /api/v1/admin/policy-releases/current` (new endpoint, section 11) on every pod through the Service with `?pod=` fan-out from a Job; assert all pods report the expected release id and revisions.
3. Policy smoke: a Job in the namespace runs `opa eval` fixtures against each sidecar through `oc exec` and compares to `expected/`.
4. Publish a check run and a Slack or Teams message [A: channel]. Promotion is marked successful only when D passes.

### Workflow E, `rollback.yml` (`workflow_dispatch`: `environment`, `targetReleaseId`; protected environment)
1. Assert the target exists in `releases/<env>/history.ndjson` (previously promoted and verified) and all artifacts still exist with matching checksums.
2. Same mechanics as C step 3 with a `rollback: true` annotation and a required reason; never touches artifacts. Governance's own last-good protection (section 11.4) covers the window until the roll completes.

### Application CI/CD, `policy_kafka/.github/workflows/ci.yml` and `deploy.yml`
- `ci.yml` (pull_request, push main): `mvn -B -ntp verify` (unit + Testcontainers IT, needs Docker on the runner), `pnpm --dir admin-ui ci && pnpm lint && pnpm test && pnpm build`, Rego tests through `policies/build.sh` while policies still live here, CodeQL (Java, JS), `mvn dependency-check` or Snyk [A: approved tool], Trivy on the built image, SBOM (`syft`).
- `deploy.yml` (push main → dev; `workflow_dispatch` or tag → staging, production with protected environments): build images (`governance`, `admin-ui`) tagged `<git sha>` and `<semver>` with `docker/build-push-action`, push to the approved registry [A: registry URL; auth via OIDC if the registry supports it, else environment secret]; authenticate to OpenShift with a short-lived token from a Vault or OpenShift OIDC federation if available, otherwise a namespaced ServiceAccount token stored as an environment secret with rotation [A]; `helm upgrade --install governance deploy/helm/governance -f values-<env>.yaml --set image.tag=<sha> --wait --atomic --timeout 10m`; `oc rollout status`; `GET /actuator/health`, `/health/readiness`; Workflow D; smoke: OIDC token from the environment's IdP test client, `POST /api/v1/manifests` with a known-good manifest in a sandbox team, expect 202 and later `COMPLETED` (dev, staging) or a read-only `GET /api/v1/admin/policies` (production); report in the job summary and the Release notes.

---

## 10. Artifactory publishing and promotion strategy [D, paths are proposals A]

```
opa-bundles/                              (generic repository, "deploy without overwrite" permission only for the CI user;
  core/<revision>/core-<revision>.tar.gz   an Artifactory user plugin or retention rule marks paths immutable)
  core/<revision>/core-<revision>.tar.gz.sha256
  core/<revision>/signatures.json
  manifests/<revision>/...  naming/...  quotas/...  resources/...  changes/...
  releases/candidates/<releaseId>.json
  releases/dev/<releaseId>.json          releases/dev/history.ndjson
  releases/staging/...                   releases/production/...
opa-contracts/<version>/input.schema.json ...
```
- `releaseId` = `rel-<yyyyMMdd>.<run_number>-<short sha>`; manifest content: `{releaseId, createdAt, gitSha, contractVersion, bundles: {core: {revision, sha256, url}, ...}}`.
- **How OPA finds the approved revision**: it does not resolve a pointer. The OPA sidecar config lists the six `resource` paths with the exact revision directories, rendered by Helm from `releases/<env>.json` in the application repo. Promotion = change that file = new ConfigMap checksum = rolling restart. Each replica starts, downloads exactly the pinned artifacts, verifies signatures, and becomes ready only when all six are active.
- Credentials: OPA uses `services.artifactory.credentials.bearer.token: ${ARTIFACTORY_TOKEN}` from a Secret (read-only scoped token for `opa-bundles/**`), rotated by External Secrets Operator or the platform's secret sync [A]; HTTPS only; `allow_insecure_tls: false`; CA bundle mounted if the enterprise CA is private [A].
- Retention: never delete a revision referenced by any `releases/*/history.ndjson`; prune candidates older than 90 days.

---

## 11. OpenShift sidecar deployment design and Helm chart [D]

Repository has no deployment convention beyond Compose, so a Helm chart is introduced at `deploy/helm/governance/` (Kustomize overlays would duplicate the OPA config per environment; Helm values keep one template).

### 11.1 Pod
| Container | Image | Key settings |
|---|---|---|
| `governance` | `<registry>/governance:<sha>` (existing `governance/Dockerfile`, non-root uid 10001 [F]) | `OPA_URL=http://127.0.0.1:8181`, `GOVERNANCE_POLICY_RELEASE_FILE=/etc/governance/release.json`, `SPRING_PROFILES_ACTIVE=openshift`, DB and Kafka settings from Secrets; probes: liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` (includes `policyRelease` indicator), startup probe 60 × 5 s; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`; requests 500m / 768Mi, limits 2 / 1.5Gi |
| `opa` | `openpolicyagent/opa:1.22.0-static` (pinned digest) | `run --server --addr 127.0.0.1:8181 --diagnostic-addr 0.0.0.0:8282 --config-file /config/opa.yaml --log-format json --log-level info --disable-telemetry`; readiness `GET :8282/health?bundles&plugins` (fails until every configured bundle is activated), liveness `GET :8282/health`; requests 100m / 128Mi, limits 500m / 512Mi; `persistence_directory: /var/opa` on an `emptyDir` |
| Pod | | `securityContext: runAsNonRoot, seccompProfile RuntimeDefault, no runAsUser (let the restricted-v2 SCC assign the UID range), fsGroup omitted`; containers `allowPrivilegeEscalation: false, capabilities drop ALL, readOnlyRootFilesystem: true`; `emptyDir` for `/tmp` (both) and `/var/opa`; `shareProcessNamespace: false` |

The diagnostic listener on 8282 exposes only `/health` and `/metrics` (no Data or Policy API), which is why it may bind the pod IP for kubelet probes; the NetworkPolicy still restricts it.

### 11.2 Objects in the chart
`Deployment` (rolling, `maxUnavailable: 0`, `maxSurge: 1`, `minReadySeconds: 10`, pod anti-affinity preferred, `checksum/config` and `checksum/release` annotations), `Service` (8080 only), `Route` (edge TLS, Governance only) toggled by values, `ConfigMap` `governance-config` (application overrides) and `opa-config` (rendered from `release.json` + values), `ConfigMap` `policy-release` (the release manifest mounted for Governance), `Secret` references (`governance-db`, `governance-kafka`, `artifactory-opa-reader`, `opa-signing-public-key` as ConfigMap) created by ESO or by the platform [A], `ServiceAccount` with no extra SCC, `NetworkPolicy` (ingress 8080 from the Router namespace and the admin-ui pods; 8282 from `openshift-monitoring` only; egress to Postgres, Kafka, Schema Registry, Artifactory, IdP), `PodDisruptionBudget` (`minAvailable: 1`), `ServiceMonitor` (Governance `/actuator/prometheus`, OPA `:8282/metrics`), `HorizontalPodAutoscaler` optional. Admin UI gets its own small chart or a sub-chart with its nginx Route; its `/api` proxy targets the Governance Service.

### 11.3 Rendered OPA configuration (template)
```yaml
services:
  artifactory:
    url: https://<artifactory>/artifactory/opa-bundles      # value, [A]
    credentials: { bearer: { token: "${ARTIFACTORY_TOKEN}" } }
    response_header_timeout_seconds: 30
bundles:
  core:      { service: artifactory, resource: core/{{ .rev.core }}/core-{{ .rev.core }}.tar.gz,
               persist: true, polling: { min_delay_seconds: 60, max_delay_seconds: 120 }, signing: { keyid: governance-policies } }
  manifests: { ... }  naming: { ... }  quotas: { ... }  resources: { ... }  changes: { ... }
keys:
  governance-policies: { algorithm: RS256, key: "${OPA_SIGNING_PUBLIC_KEY}" }
persistence_directory: /var/opa
decision_logs: { console: true, reporting: { min_delay_seconds: 5, max_delay_seconds: 10 } }
status: { console: true }
```
Polling stays enabled although paths are pinned, so a transient download failure at start is retried; a pinned path never changes content, so polling is cheap (ETag 304).

### 11.4 Readiness, replicas, staleness
- New `PolicyReleaseReadinessIndicator` in Governance: loads `release.json`, asks the sidecar `GET /v1/status`-equivalent via `data.system.bundles` (already used by `OpaClient.loadedRevision` [F]) for all six, requires exact revision equality and `data.governance.core.ready == true` with the declared `contract_version` major supported by this Governance build. Readiness fails otherwise, so the rolling update never shifts traffic to a pod with an incomplete or mixed set. Replicas therefore activate independently and only consistent pods serve; mixed releases across replicas during a roll are safe because each decision records its release id and the release is a tested set.
- Freshness policy (`governance.opa.release.max-age`, default `P7D`, and `allow-persisted-on-start: true`): with `persist: true` OPA can restart on cached bundles when Artifactory is down; Governance accepts them only if their `built_at` is within `max-age` and they match the release manifest; otherwise not ready. During a running pod, a failed poll keeps the already-active bundles (OPA behaviour) and Governance stays ready; `verifyActivation`'s 90 s auto-rollback [F] is retired in the Artifactory mode (rollback is Workflow E) and kept in local mode.

### 11.5 Governance code changes for the sidecar mode
| File | Change |
|---|---|
| `configuration/GovernanceProperties.Opa` | `decisionPath=/v1/data/governance/core/decision`, `bundles` (list, default the six names), `releaseFile`, `release.maxAge`, `mode = local | release` |
| `infrastructure/opa/OpaClient` | `loadedRevisions()` for all bundles, `ready()` querying `data.governance.core.ready` |
| `application/evaluation/PolicyEvaluationService` | compare the provenance map to the expected set; `ERROR` on any mismatch or `ready=false` |
| `domain/decision/GovernanceDecision` + migration `V4` | `policy_release_id text`, `policy_revisions jsonb` (keep `policyRevision` as the release id for compatibility) |
| new `infrastructure/opa/PolicyReleaseReadinessIndicator` | section 11.4 |
| new `api/administration/PolicyReleaseController` | `GET /api/v1/admin/policy-releases/current` (expected vs active per bundle), auditor role |
| `application/policy/PolicyBundleService`, `OpaBundleEndpoint`, upload endpoints | active only when `mode=local`; in `release` mode the upload and activate endpoints return 409 `POLICY_SOURCE_IS_GIT` and the UI hides them (`features.localPolicyAdmin=false` in `/env.js`) |
| `application/evaluation/OpaInputAssembler` | input v2 (team, usage, ownership, now, contractVersion) |
| new `application/teams/*`, `api/administration/TeamConfigurationAdminController`, migration `V4` tables `team_configuration`, `repository_usage`, `quota_reservation`, `shadow_finding` | sections 6.1–6.2, 4.3 |
| new `infrastructure/kafka/RepositoryStateConsumer` | reads `teams.repo.manifest` |

---

## 12. Local development and admin console [D]

- `deploy/docker-compose.yml`: OPA keeps polling Governance's local bundle endpoint, now for six bundle names (`/internal/opa/bundles/{core|manifests|...}`); `policies/build.sh` builds six bundles and a local `release.json`; a new `deploy/scripts/upload-release.sh` uploads all six plus the release manifest to Governance in local mode. Optional profile `compose --profile artifactory-like` adds an nginx container serving `policies/dist/` with the Artifactory path layout and bearer check, so the exact OpenShift OPA config can be exercised locally (used by the IT for Artifactory-unavailable scenarios).
- Admin console additions (existing `admin-ui/`, pages gated by `features.localPolicyAdmin`): bundle list with the six bundles, revisions, expected versus active per bundle (release page), **policy workbench** (local only): edit Rego drafts in the browser (CodeMirror, local files under `policies/bundles` via a dev-only Governance endpoint that shells out to the pinned OPA image: `check`, `test`, `build`), publish a local release, test a JSON input against `data.governance.core.decision` through Governance (`POST /api/v1/admin/policy-playground/evaluate`, local mode only, auditor role), OPA activation status (exists as diagnostics). Team configuration pages (list, edit prefixes, quotas, role overrides, with `source` badge) and the existing exceptions pages are available in all modes.
- Guardrails: every local-only endpoint is behind `governance.policy-admin.mode=local` **and** a profile check that refuses to start in `local` mode when `SPRING_PROFILES_ACTIVE` contains `openshift`; the Helm chart hardcodes `release` mode; the UI hides the workbench when `/env.js` says so and the API still rejects. No path from the workbench to Artifactory exists.

---

## 13. Security and secrets

- No credentials in Git: Artifactory tokens via JFrog OIDC in Actions and ESO-synced Secrets in OpenShift [A: ESO or equivalent available]; OpenShift deploy identity via OIDC federation if the cluster offers it, otherwise a namespaced ServiceAccount token kept as a GitHub environment secret and rotated [A]; registry push via OIDC where supported [A].
- Bundle integrity: OPA signature verification mandatory in `release` mode (`signing.keyid`), public key as ConfigMap, private key in the CI secret store with a rotation procedure (two `keys` entries during rotation).
- OPA surface: Data API bound to loopback; diagnostic listener exposes health and metrics only; NetworkPolicy; no Route or Service for OPA; `--disable-telemetry`; decision logs to stdout with request id (never the JWT).
- Kafka: ACLs unchanged for `requests.manifest`; new read ACL on `teams.repo.manifest` for Governance; SASL and TLS settings from Secrets.
- Governance: JWT validation unchanged; team configuration writes require `governance-admin` and are audited; reservation and usage tables are internal.
- Supply chain: pinned actions by SHA, pinned OPA, image digests in values, SBOM and Trivy in CI, CODEOWNERS on `bundles/**` and `deploy/helm/**`.

---

## 14. Test strategy

| Area | Tests | Where |
|---|---|---|
| Rego, per bundle | unit tests next to each package; fixtures shared through `tests/fixtures` | policy repo, Workflow A |
| Cross-bundle | composition tests: all six loaded → expected decisions; a bundle missing → `ready=false`; mismatched `contract_version` major → `ready=false` | policy repo |
| Contract | schema validation of fixtures and outputs; Governance unit test validating `OpaInputAssembler` output against the pinned schema | both |
| Build and activation | Workflow B smoke (`opa run` with built artifacts); IT `multiBundleReleaseIsActivatedAndVerified` | both |
| Revision and rollback | IT: release N → N+1 with one changed bundle → decisions carry new release id; rollback to N through the local release endpoint; **partial update**: one bundle at the wrong revision → every decision `ERROR`, readiness down | Governance IT |
| Dynamic prefixes | Rego tests with two prefixes, none matching, case handling; IT: team config change applies without restart | both |
| Team quotas and cross-repository aggregation | Rego projected-usage tests; IT with two repositories of one team: second manifest exceeds the aggregate | Governance IT |
| Reservation concurrency | unit test with two concurrent `releaseStage2` on one team under Testcontainers Postgres: exactly one authorised | Governance |
| Exceptions | existing scenarios 8, 9 extended to the catalogue `bundle` field and to approval non-bypass | Governance IT |
| Hard deny, approvals, correlation, partitioning, duplicates, out-of-order | existing scenarios 4, 5, 6, 10, 11, 12 kept | Governance IT |
| OPA unavailable | existing scenario 15 kept | Governance IT |
| Artifactory unavailable, invalid signature, invalid bundle | IT using the `artifactory-like` nginx: stop it before OPA start → not ready; corrupt a signature → bundle rejected, readiness down; serve a bundle with a wrong root → OPA rejects, readiness down | Governance IT |
| OpenShift readiness and sidecar | Workflow D against a real test namespace: rollout, probes, revisions, smoke evals; a chaos step scales OPA bundle URL to a 404 revision in dev and asserts the pod never becomes ready and the rollout is held | deploy workflow |
| End to end | existing 17-scenario IT, grown to the above | Governance |

---

## 15. Deployment and rollback strategy

- Application: Helm `--atomic` rollouts, readiness gated on the policy release, PDB, `maxUnavailable 0`. Rollback: `helm rollback` or redeploy the previous image tag; the release manifest is independent of the image, so a policy rollback never requires an application rollback and vice versa.
- Policies: Workflow C changes only the environment's release file; a roll activates per pod; Workflow D verifies; Workflow E restores a previous verified release by the same path. Artifacts are never modified or deleted.
- Database: Flyway `V4` is additive (new tables and nullable columns); no destructive migration in this programme.
- Contract majors: Governance first (accepts N and N+1), policies second, Governance drops N in a later release.

---

## 16. Phased implementation tasks (ordered by dependency)

Verification commands assume this repository's tooling: `mvn -q -pl governance verify`, `policies/build.sh`, `pnpm --dir admin-ui test`, `docker compose -f deploy/docker-compose.yml up --build -d`, `helm lint`, `helm template`.

### Phase 1, Bundle split and composition (policies, Governance read side) — 1.5 to 2 weeks
Tasks: restructure `policies/` into `policies/bundles/{core,manifests,naming,quotas,resources,changes}` with `.manifest` roots; move packages per 3.6; write `core` composition, `ready`, merged `catalog`, `contract_version`; `policies/build.sh` builds six bundles and `release.json`; `scripts/check-roots.sh`; Governance: `GovernanceProperties.Opa.bundles`, decision path, provenance map check, `PolicyReleaseReadinessIndicator`, `V4` columns `policy_release_id`, `policy_revisions`; `PolicyBundleService` and `OpaBundleEndpoint` serve six names and a release; `PolicyCatalogController` reads the merged catalogue; `upload-release.sh`; admin-ui bundles page shows six bundles and expected versus active; e2e IT scenarios 2, 14, 16 updated plus `partialUpdateFailsClosed`.
Dependencies: none. Acceptance: 46+ Rego tests pass with all bundles loaded; `opa build` per bundle succeeds and a deliberate root violation fails; `mvn verify` green incl. new IT; a request with one bundle at a wrong revision yields 503 and readiness `DOWN`; the console lists six bundles. Verify: `policies/build.sh && mvn -q -pl governance verify && pnpm --dir admin-ui test`.

### Phase 2, Input contract v2, team configuration, usage, quotas — 2 to 3 weeks
Tasks: `contracts/opa/*.schema.json`; `OpaInputAssembler` v2 with `now`, `team`, `usage`, `ownership`, `contractVersion`; `team_configuration` (+ admin API, UI pages, audit, `source`); `RepositoryStateConsumer` + `repository_usage` + ACL in `deploy/kafka/acls.sh`; `quota_reservation` with row-lock admission in `ManifestEventHandler` and `ApprovalReleaseService`, release on terminal events and expiry job; Rego: `naming` over `authorizedPrefixes`, `quotas` projected usage, `manifests` ownership conflicts; shadow enforcement (`configuration.enforcement`, `shadow_finding`, metric); schema test in Governance.
Dependencies: Phase 1. Acceptance: two repositories of one team cannot jointly exceed a quota; two concurrent approvals admit exactly one; prefix change applies without restart; shadow policies never block but are audited; IT scenarios added for each. Verify: `mvn -q -pl governance verify` and the Rego suite.

### Phase 3, Policy repository extraction and Workflows A, B — 1 week (+ Artifactory onboarding [A])
Tasks: `git subtree split` of `policies/` into `governance-policies`; `validate.yml`, `publish.yml`, scripts, Regal config, CODEOWNERS, branch protection; JFrog OIDC provider or scoped token [A]; Artifactory repository `opa-bundles` with no-overwrite permissions [A]; signing key pair [A: key custody]. Acceptance: a PR with a root violation or failing test cannot merge; a merge publishes signed, checksummed, immutable artifacts and a candidate release manifest; re-running the publish for an existing revision fails. Verify: workflow runs plus `jf rt search opa-bundles/core/*`.

### Phase 4, Helm chart and application CI/CD — 1.5 weeks
Tasks: `deploy/helm/governance` per section 11 with `values-dev.yaml`, `values-staging.yaml`, `values-production.yaml`; `releases/<env>.json`; OPA config template; `openshift` Spring profile (`mode=release`, JSON logging via Logback encoder, actuator groups); `ci.yml`, `deploy.yml`; ESO `ExternalSecret` manifests or documented Secret contract [A]; admin-ui chart. Acceptance: `helm lint` and `helm template` clean; `oc apply --dry-run=server` passes under `restricted-v2` SCC in the dev namespace [A]; a deploy to dev becomes ready only after the sidecar activates all six bundles; OPA Data API unreachable from another pod. Verify: `helm lint deploy/helm/governance && helm template ... | oc apply --dry-run=server -f -`, Workflow D.

### Phase 5, Promotion, verification, rollback (Workflows C, D, E) — 1 week
Tasks: `promote.yml`, `verify.yml`, `rollback.yml`, `PolicyReleaseController`, smoke fixture Job manifest, notifications. Acceptance: promotion to staging requires approval, opens the release PR, deploy rolls, D passes and marks the promotion; E restores the previous release and D passes; no artifact mutated (checked by checksum). Verify: workflow runs plus `GET /api/v1/admin/policy-releases/current` on every pod.

### Phase 6, Resource-type expansion — 2 weeks, external dependency
Tasks: additive Avro records and arrays (7.2) with the compatibility test; Governance domain, validator, assembler, Rego in `naming`, `resources`, `quotas`, `changes`; simulators updated to carry the new arrays; admin-ui exception form and catalogue. Dependencies: Orchestrator and Processor owners agree on the schema and resource semantics [A]. Acceptance: BACKWARD compatibility test passes; end-to-end manifest with schemas and connectors completes in the simulator stack; policies for each type have tests.

### Phase 7, Orchestrator rule migration — depends on source access [A]
Tasks: fill the matrix (4.2), implement missing Rego, shadow in dev and staging, compare `MANIFEST_REJECTED` after `ALLOW` to zero over a soak window, flip enforcement stage 1 then stage 2, remove duplicated rules in the Orchestrator repository. Acceptance: no discrepancy for the soak window; Orchestrator retains technical validations only.

### Phase 8, Hardening and documentation — 1 week
Tasks: ADRs 0007 (six bundles and composition), 0008 (release manifest and Artifactory distribution), 0009 (team configuration ownership), 0010 (quota reservations); runbook (bundle outage, rollback, key rotation); docs for the policy repo; `docs/flow-animation.html` component map updated with Artifactory and the sidecar; retire `verifyActivation` auto-rollback in release mode.

---

## 17. Acceptance criteria per phase
Listed under each phase above. Programme-level: every decision row carries a release id and six revisions; a pod with an incomplete bundle set never receives traffic; a quota cannot be exceeded by concurrent plans; no secret in Git; policy changes reach production only through Workflows B, C, D; local editing is impossible in deployed environments.

---

## 18. Risks, assumptions, decisions

### Risks
| Risk | Likelihood | Mitigation |
|---|---|---|
| Orchestrator and Processor source unavailable for the migration matrix and resource-type expansion | High | Phases 6 and 7 are isolated; request access now; keep simulators as the executable contract |
| Artifactory OIDC or no-overwrite features not enabled | Medium | Fallback to scoped expiring tokens in GitHub environments; immutability enforced by the pre-upload HEAD check and a path permission that denies delete and overwrite |
| OpenShift cluster lacks OIDC federation for GitHub | Medium | Namespaced ServiceAccount token in a protected environment with rotation; documented |
| Quota projection depends on Governance consuming `teams.repo.manifest` promptly | Medium | Freshness guard returns `ERROR` and retries rather than evaluating stale usage; metric and alert on consumer lag |
| Decision latency grows with six bundles and larger input | Low | Measure in IT (`governance_opa_evaluation_seconds` exists [F]); `-O1` builds; input size bounded by manifest size |
| Mixed releases across replicas during a roll | Low | Readiness gate plus release id in every decision; releases are tested sets |
| Contract drift between the two repositories | Medium | Versioned schemas, Governance schema test, `ready=false` on major mismatch |

### Assumptions to confirm (missing configuration)
1. Artifactory base URL, repository key, OIDC provider or token policy, private CA.
2. OpenShift cluster API URL, namespaces per environment, SCC (`restricted-v2` assumed), ingress and monitoring namespaces for NetworkPolicy, External Secrets availability, container registry and its auth.
3. Real Orchestrator and Processor repositories and owners; whether `teams.repo.manifest` carries partitions and all resource types in production.
4. Whether an enterprise team registry exists (source of prefixes and quotas) or Governance becomes the system of record.
5. Enterprise OIDC issuer, client ids for the admin console and for CI smoke tests, role claim names.
6. Signing key custody (who holds the private key, rotation cadence).
7. Approved SAST and dependency scanning tools, notification channel.

### Decisions proposed
- Separate `governance-policies` repository; release manifest as the unit of promotion; pinned artifact paths in the OPA config instead of a mutable pointer; Helm over Kustomize; Governance as system of record for team configuration unless an external registry is confirmed; quota admission arithmetic under a row lock in Java with values and rules from OPA; shadow mode per policy id rather than a parallel Java engine; local policy workbench exists only in `local` mode.
