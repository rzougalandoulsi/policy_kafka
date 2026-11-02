# Kafka resource-management platform with OPA governance

Three services around a Kafka backbone:

| Service | Role | Module |
|---|---|---|
| **Governance** (new) | Public REST entry point: OAuth2 JWT, OPA policy evaluation (two stages), approvals, scoped exceptions, audit, Kafka handshake | `governance/` |
| **Orchestrator** (existing, not implemented here) | Diff desired vs confirmed state, propose immutable plans, release only authorized plans, reconcile results | stand-in: `orchestrator-simulator/`; requirements: `docs/orchestrator-integration.md` |
| **Processor** (existing, not implemented here) | Executes actions against Confluent Cloud | stand-in: `processor-simulator/` |
| **Admin UI** (new) | Browser console for team configuration, policy configuration (incl. shadow mode), Rego bundles and releases, scoped exceptions, approval rules, shadow findings and audit (OIDC login, roles from the token, API stays the enforcer) | `admin-ui/` |

Supporting pieces: `contracts/` (shared Avro schemas and the OPA input/output JSON schema, contract 2.0), `policies/` (six Rego v1 bundles, tests, fixtures and the CI/publishing tooling of the future `governance-policies` repository, see `policies/README.md`), `deploy/` (Docker Compose with SASL/ACLs, OPA, mock OIDC; Helm charts for OpenShift under `deploy/helm/`), `docs/`.

## Flow

```
client ──POST /api/v1/manifests──▶ Governance ──Stage 1 (OPA)──▶ requests.manifest: SUBMIT_MANIFEST
                                                                     │
Orchestrator: diff → immutable plan (planId, digest, stateVersion) ◀─┘
      │ response.manifest: PLAN_PROPOSED
      ▼
Governance ──Stage 2 (OPA, full plan)──▶ requests.manifest: AUTHORIZE_PLAN | REJECT_PLAN   (or pending approval)
      │
Orchestrator verifies decisionId + planId + digest + stateVersion → releases Actions to teams.changes → Processor
      │ request.status → reconcile → response.manifest: MANIFEST_COMPLETED | MANIFEST_FAILED
      ▼
client ──GET /api/v1/requests/{id}──▶ Governance
```

Exactly two topics carry the Governance↔Orchestrator protocol: `requests.manifest` (commands) and `response.manifest` (events). Governance additionally reads the Orchestrator's confirmed state from `teams.repo.manifest` to project team usage for quotas (never writes it). See `docs/kafka-contract.md`.

## Build and test

```bash
mvn -q verify                 # unit tests + Testcontainers end-to-end test (Docker required)
policies/test.sh              # Rego unit tests (OPA pinned by policies/OPA_VERSION, Docker when no binary)
policies/build.sh [revision]  # check + test + fixtures + build the six bundles into policies/dist/ plus release.json
policies/scripts/validate.sh  # everything the policy repository's Workflow A runs (fmt, strict check, coverage, roots, schemas, Regal, gitleaks)
deploy/helm/scripts/render-check.sh   # helm lint + template + kubeconform for the governance and admin-ui charts (all environments)
cd admin-ui && pnpm install && pnpm lint && pnpm test && pnpm build   # admin console (Node 20+, pnpm)
```

## Run locally

```bash
mvn -q package -DskipTests && policies/build.sh
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up --build -d
deploy/scripts/upload-release.sh                      # upload the six bundles, activate them as one release, wait for OPA
deploy/scripts/submit-manifest.sh docs/examples/manifest.json
```

Tokens for the mock identity provider (host port 18089): `deploy/scripts/token.sh dev-1|approver-1|admin-1|outsider`. Postgres is published on 15432.
OpenAPI UI: http://localhost:8080/swagger-ui.html. Metrics: http://localhost:8080/actuator/prometheus.

Admin console: http://localhost:3000 — "Sign in with OIDC" signs you in as `admin-1` (the mock provider has no login
page); auditors can use `deploy/scripts/token.sh` with the dev token form. See `docs/admin-ui.md`.

## Documentation

- `docs/repository-analysis.md` — what the existing Orchestrator prototype did and the gaps found
- `docs/kafka-contract.md` — topics, Avro envelopes, correlation, partitioning, idempotency
- `docs/orchestrator-integration.md` — what the real Orchestrator must implement to join the handshake (the simulator mirrors it)
- `docs/governance.md` — request lifecycle, decision model, policies, exceptions, approvals, dynamic policy management
- `docs/flow-animation.html` — animated walkthrough of the handshake as a sequence diagram and as a component map (services, Postgres tables, Kafka topics, clients, and the policy release supply chain: policy repository workflows, Artifactory, release file, OPA sidecar) that traces each scenario: happy path, hard deny, scoped exception, stage 2 approval, OPA unavailable; open the file from a checkout, GitHub shows only its source
- `docs/runbook.md` — operations, the single entry point: metrics, health, dead letters, failure modes, release-mode operations (Artifactory outage, signature failure, a promotion that never becomes ready, rollback, signing key rotation, contract major bump)
- `policies/docs/` — the policy repository's own guides: bundle authoring, contract, Workflows A to E operations, extraction checklist
- `docs/admin-ui.md` — the admin console: pages, roles, sign-in and proxy setup, configuration, troubleshooting
- `docs/adr/` — architecture decision records
