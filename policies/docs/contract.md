# Contract guide: Governance and the policy bundles

The contract is the JSON that crosses the OPA Data API: the input Governance assembles for
`POST /v1/data/governance/core/decision?provenance=true` and the decision core returns. It is written down as JSON
Schemas in `contracts/` and carried as a version string in every bundle.

| File | Validates | Who checks it |
|---|---|---|
| `contracts/input.schema.json` | every `tests/fixtures/inputs/*.json` | Workflow A (`scripts/check-schemas.sh`); Governance's `OpaInputContractTest` validates real `OpaInputAssembler` output against its pinned copy |
| `contracts/output.schema.json` | every decision the fixtures produce and every `expected/*.json` | Workflow A |
| `contracts/catalog.schema.json` | the merged catalogue `data.governance.core.policy_catalog` | Workflow A |
| `contracts/release-manifest.schema.json` | candidate and promotion manifests, `dist/release.json`, the application release files | Workflows A and B, the local build |
| `contracts/CHANGELOG.md` | the history of the contract | reviewers |

## Versions

- `contract_version := "MAJOR.MINOR"` is declared by `core` and by every domain bundle. Core is `ready` only when every
  required bundle declares the same **major** as core (`tests/composition_test.rego`); minors may differ.
- Governance declares the major it emits (`GovernanceProperties.Opa.contractVersion`, `input.contractVersion`) and the
  majors it accepts from core (`governance.opa.supported-contract-majors`, 1 and 2 during the 1 to 2 transition).
  A decision whose `contractVersion` major is not accepted is an `ERROR` (fail closed).
- **Minor bump (additive)**: a new optional input field, a new quota key, a new optional output field. Governance may
  start emitting the field before any bundle reads it; bundles read it with `object.get` and a default so they keep
  working against older Governance builds. Update the schema, the fixtures that should exercise the field and the
  changelog; bump the minor in every bundle in the same pull request (they share one `lib.rego`).
- **Major bump (breaking)**: a renamed or removed field, a changed meaning. Order: Governance first (emits N+1, accepts
  N and N+1), then one policy release that moves all six bundles to N+1 together, then Governance drops N
  (`docs/runbook.md` in policy_kafka, "Contract major bump"). A release never mixes majors: core refuses the set.

## Input (2.0) in one screen

```
contractVersion, stage (REQUEST | ACTION_PLAN), now (RFC 3339, supplied by Governance)
principal {id, roles[]}
team {id, authorizedPrefixes[], quotas{key: n}, allowedRoles[], environments{}, configurationVersion, source}
repository {id, ownedByTeam, stateVersion}
environment
manifest {teamId, repositoryId, environment, topics[], identityPools[], rbac[], schemas[], serviceAccounts[], connectors[], flinkStatements[]}
actions[] {actionId, resourceType, resourceName, actionType, payload}          (stage ACTION_PLAN)
plan {planId, planDigest, stateVersion}                                         (stage ACTION_PLAN)
usage {current{}, reserved{}, delta{}, byRepository{}, asOfStateVersions{}, snapshotAge}
ownership {resources: {"TYPE:name": repositoryId}}
request {requestId, manifestDigest}
configuration {naming, rbac, limits, destructive, approvals, enforcement{policyId: enforce|shadow}}
exceptions[] {id, policyId, principalId, teamId, environment, resourcePattern, actionTypes[]}
```

Everything privileged comes from Governance's own sources (JWT, `team_configuration`, `repository_usage`,
`quota_reservation`, `policy_exception`); bundles never fetch anything. The empty resource arrays (`schemas`,
`serviceAccounts`, `connectors`, `flinkStatements`) are reserved for the Phase 6 resource-type expansion and stay empty
until the Avro contracts carry them.

## Output in one screen

```
decision: ALLOW | DENY | REQUIRE_APPROVAL | ERROR
violations[] {policyId, severity (DENY|HARD_DENY), message, resourceType, resourceName, bypassable, enforcement, exempted, exceptionId}
approvals[]  {policyId, message, resourceType, resourceName, enforcement}
matchedPolicies[], appliedExceptions[]
ready (bool), bundles {name: contract_version}, contractVersion, revisions {name: revision}   (revisions: runtime only, absent under opa test and removed from fixtures)
```

Governance recomputes the outcome from `violations` and `approvals` and refuses the decision when its aggregate differs
from `decision`; it also compares `revisions` with the expected release and requires `ready == true`.

## Keeping the two copies equal

While the policies live inside policy_kafka, Governance's pinned copy under `contracts/opa/` and this directory must be
identical: `scripts/check-contract-sync.sh` (Workflow A) fails otherwise. After the extraction
(`docs/extraction-checklist.md`) the schemas are published as a versioned artifact (`opa-contracts/<version>/`, an
Artifactory input still to be confirmed) and Governance pins the version in `pom.xml`; the sync check then becomes a
no-op because the sibling directory no longer exists.

## Checklist for a contract change

1. Edit the schema(s) and `CHANGELOG.md` (version, date, what changed, additive or breaking).
2. Bump `contract_version` in all six bundles (`grep -rn 'contract_version :=' bundles/`).
3. Adapt `tests/fixtures_test.rego` helpers, the fixtures (`scripts/eval-fixtures.sh --update`, review) and the tests.
4. Governance: `OpaInputAssembler`, `OpaInputContractTest`, `supported-contract-majors` for a major; copy the schemas to
   `contracts/opa/` while the repositories are one.
5. `scripts/validate.sh` green; for a major, follow the runbook order across environments.
