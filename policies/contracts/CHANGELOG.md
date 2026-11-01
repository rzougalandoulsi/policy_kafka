# OPA contract changelog

The JSON schemas in this directory are the contract between Governance (`OpaInputAssembler`, decision parsing) and the
policy bundles (`data.governance.core.decision`). Additive changes (new optional fields, new quota keys) bump the minor;
renamed or removed fields bump the major. A major bump ships Governance first (it must accept both majors through
`governance.opa.supported-contract-majors`), then the domain bundles, then core. Governance keeps its own pinned copy
(`contracts/opa/` in policy_kafka, packaged in the contracts jar); `scripts/check-contract-sync.sh` keeps both copies
equal while the two code bases share one repository.

## 2.0 (2026-11-01)
- Input: `contractVersion`, `now`, `team{id, authorizedPrefixes, quotas, allowedRoles, environments, configurationVersion, source}`,
  `repository{id, ownedByTeam, stateVersion}`, `usage{current, reserved, delta, byRepository, asOfStateVersions, snapshotAge}`,
  `ownership{resources}`, `configuration.enforcement`, empty `schemas`, `serviceAccounts`, `connectors`, `flinkStatements` arrays.
- Output: `ready`, `bundles`, `contractVersion`, `enforcement` on every violation and approval requirement.
- New: `catalog.schema.json` (merged policy catalogue entries) and `release-manifest.schema.json` (tested set of revisions).

## 1.0
- Single bundle `governance`; `{stage, principal, team{id}, environment, manifest, actions, plan, request, configuration, exceptions}`.
