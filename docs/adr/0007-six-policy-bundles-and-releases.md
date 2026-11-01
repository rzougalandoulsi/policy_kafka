# ADR 0007: Six independently versioned policy bundles composed by core, verified as a release

## Context
One bundle (`governance`) held every policy. The target platform needs independently versioned bundles per concern (core, manifests, naming, quotas, resources, changes), distributed from Artifactory in deployed environments, while Governance must keep failing closed on any inconsistency.

## Decision
- Six bundles under `policies/bundles/<name>/`, each with exactly one `.manifest` root `governance/<name>`; `scripts/check-roots.sh` enforces the set, the roots and the absence of cross-bundle imports.
- Rego functions do not resolve across bundles at build time, so each bundle carries an identical `lib.rego` under its own root (`scripts/check-lib-sync.sh` keeps the copies equal) instead of importing a shared helper package.
- `core` owns composition: it references `data.governance[ns].{violations,approvals,catalog,contract_version}` dynamically, never by import, so a missing or incompatible bundle becomes `ready == false` and the outcome `ERROR` rather than a compile error. Core must not define rules with those names itself (that would be recursive). Entry points: `data.governance.core.decision`, `.ready`, `.policy_catalog`.
- Each bundle declares `contract_version`; core requires the same major. A major bump is rolled out Governance first, then policies.
- The **policy release** is the unit Governance verifies: one revision per bundle, id `rel-<sha256(sorted name=revision)[0:12]>`, computed identically by `policies/build.sh` (`dist/release.json`) and by `PolicyRelease.of`. Every decision records the release id and the per-bundle revisions OPA reported; a single mismatch, a missing bundle or `ready == false` is an `ERROR`.
- Local mode keeps Governance as the bundle server (`/internal/opa/bundles/{name}`), one ACTIVE version per bundle name, atomic activation of a full set (`POST /api/v1/admin/policy-releases`) and per-bundle rollback. Readiness (`policyRelease`) is DOWN until OPA runs exactly the active release, so a replica never serves with a partial set.

## Consequences
- Promotion in deployed environments will move a release manifest, not a bundle (see the platform plan, Artifactory phase).
- `MAX_PARTITIONS_PER_PLAN` lives in `quotas` until projected team usage arrives with input contract 2.0; `manifests` has no rules yet but is part of the required set so the tooling exercises all six roots from day one.
