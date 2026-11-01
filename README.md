# Kafka resource-management platform with OPA governance

Three services around a Kafka backbone:

| Service | Role | Module |
|---|---|---|
| **Governance** (new) | Public REST entry point: OAuth2 JWT, OPA policy evaluation (two stages), approvals, scoped exceptions, audit, Kafka handshake | `governance/` |
| **Orchestrator** (existing, not implemented here) | Diff desired vs confirmed state, propose immutable plans, release only authorized plans, reconcile results | stand-in: `orchestrator-simulator/`; requirements: `docs/orchestrator-integration.md` |
| **Processor** (existing, not implemented here) | Executes actions against Confluent Cloud | stand-in: `processor-simulator/` |

Supporting pieces: `contracts/` (shared Avro schemas), `policies/` (Rego v1 bundle + tests), `deploy/` (Docker Compose with SASL/ACLs, OPA, mock OIDC), `docs/`.

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

Exactly two topics carry the Governance↔Orchestrator protocol: `requests.manifest` (commands) and `response.manifest` (events). See `docs/kafka-contract.md`.

## Build and test

```bash
mvn -q verify                 # unit tests + Testcontainers end-to-end test (Docker required)
policies/test.sh              # Rego unit tests (OPA in Docker)
policies/build.sh [revision]  # check + test + build policies/dist/governance-bundle.tar.gz
```

## Run locally

```bash
mvn -q package -DskipTests && policies/build.sh
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up --build -d
deploy/scripts/upload-bundle.sh                       # upload + activate the policy bundle, wait for OPA
deploy/scripts/submit-manifest.sh docs/examples/manifest.json
```

Tokens for the mock identity provider (host port 18089): `deploy/scripts/token.sh dev-1|approver-1|admin-1|outsider`. Postgres is published on 15432.
OpenAPI UI: http://localhost:8080/swagger-ui.html. Metrics: http://localhost:8080/actuator/prometheus.

## Documentation

- `docs/repository-analysis.md` — what the existing Orchestrator prototype did and the gaps found
- `docs/kafka-contract.md` — topics, Avro envelopes, correlation, partitioning, idempotency
- `docs/orchestrator-integration.md` — what the real Orchestrator must implement to join the handshake (the simulator mirrors it)
- `docs/governance.md` — request lifecycle, decision model, policies, exceptions, approvals, dynamic policy management
- `docs/runbook.md` — operations: metrics, health, dead letters, OPA revision diagnostics, failure modes
- `docs/adr/` — architecture decision records
