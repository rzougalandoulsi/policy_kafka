# Repository analysis (before the Governance work)

Source inspected: the Orchestrator prototype at `kafka/ssengine` (analysed only; not implemented here, see `orchestrator-integration.md`). No Processor source exists; its contract was taken from the Orchestrator's schemas and the embedded README.

| # | Item | Finding |
|---|---|---|
| 1 | Maven modules / packages | Single module `com.itland:ssengine`, Spring Boot 3.4.5, Java 21, package `com.itland.orchestrator.{config,diff,producer,topology}`, Avro generated into `com.itland.orchestrator.avro` (`stringType=String`). No tests, no Dockerfile. |
| 2 | Kafka topics | `teams` (compacted, unused), `<team>.repo.manifest` (regex source of desired manifests, ingested directly), `teams.repo.manifest` (**compacted confirmed state keyed `teamId|repoId`; a state table, not a command topic**), `teams.changes` (ACTION records to the Processor + END_OF_BATCH markers, header `intendedFor`), `request.status` (Processor → `ProcStatus`, keyed by correlationId), `<team>.status` (fan-out). |
| 3 | Avro schemas | `Action{type,payload}`, `EndOfBatch{type,correlationId,expectedCount}`, `ProcStatus`, `BatchState`, `RepoConfirmed{teamId,repoId,topics,pools,rbacsKey}`, `RepoDesired{teamId,repoId}`. `RepoDesired` lacked the `topics`/`identityPools` fields that `ManifestDiffer` called, so the prototype did not compile against its own schemas. |
| 4 | Manifest structure | Intended model (from `tmp`): topics `{name, partitions, config}`, identity pools `{name, principals, rbacs[{principal, resource, role}]}`. No environment. |
| 5 | Repo vs team semantics | Confirmed state per `teamId|repoId`; a repo's manifest is diffed only against its own confirmed record, so it cannot delete another repo's resources. |
| 6 | Validation rules | None. |
| 7 | Streams topology / stores | `confirmed-manifests` (KTable), `batch-accumulator`, `expected-by-corr`. Bugs: END_OF_BATCH read with the `Action` serde, `SpecificAvroSerde` never configured with the registry URL, the diff ran twice per message (two correlation ids), the feedback stream was never written, `BatchState.teamId/repoId` were never populated. |
| 8 | Partitioning | Key `teamId|repoId`; `request.status` keyed by correlationId. |
| 9 | Correlation ids | Random UUID per diff batch; no requestId/planId. |
| 10 | Final status messages | Per-action `ProcStatus` to `<team>.status`; reconciled `RepoConfirmed` to `teams.repo.manifest`. No request-level terminal event. |
| 11 | Error handling / retry | None (`RuntimeException` wrapping, no DLQ, no deserialization handler). |
| 12 | Security | None. |
| 13 | Deployment | `application.yml` with localhost defaults only. |

Toolchain: Java 21, Maven 3.9, Docker; no local `opa` binary (OPA tooling runs through the `openpolicyagent/opa` image).
