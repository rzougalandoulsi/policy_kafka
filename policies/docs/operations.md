# Operations: Workflows A to E

What each workflow does, what it writes where, what it needs, and what to do when it fails. Incident procedures that
involve the cluster (a pod that never becomes ready, Artifactory outages, signature failures, rollback mechanics, key
rotation, contract bumps) are in the application repository's `docs/runbook.md`, which is the operational entry point;
this page is the workflow-level detail it points to.

## State model

| Where | What | Written by | Mutable? |
|---|---|---|---|
| `opa-bundles/<bundle>/<revision>/<revision>.tar.gz`, `.tar.gz.sha256`, `<revision>.signatures.json` | one bundle revision, signed and checksummed | Workflow B | never (HEAD pre-check + deploy-without-overwrite) |
| `opa-bundles/releases/candidates/<releaseId>.json` | candidate manifest: six revisions, checksums, urls, `keyId` per bundle, `previousReleaseId` | Workflow B | never |
| `opa-bundles/releases/<env>/<releaseId>.json` | promotion record (the manifest plus `promotion{environment, from, promotedAt, promotedBy, kind, reason}`) | Workflow C | never; a second promotion of the same id to the same environment is refused |
| `opa-bundles/releases/<env>/events/<ts>-<rand>-promote\|rollback\|verified-<releaseId>.json` | timeline, one file per event, ordered by the timestamp in the name | Workflows C, D, E | never |
| `opa-bundles/releases/<env>/verified/<releaseId>.json` | Workflow D passed for this release in this environment | Workflow D (first pass only) | never |
| `deploy/helm/governance/releases/<env>.json` (policy_kafka) | **the current selection**: what the pods of that environment run | pull requests opened by C and E | yes, through Git review |
| GitHub Release draft `release/<releaseId>` | candidate manifest, checksums, signatures for audit | Workflow B | draft |

The intended release of an environment is the release file in Git; the actual one is what every pod's readiness probe
reports (`policyRelease.releaseId`); the history is the set of immutable files above, ordered by their timestamps.
There is no `history.ndjson` and no mutable "latest" pointer in Artifactory (the repository denies overwrite).

## Inputs (configured on the repository, referenced by name only)

| Scope | Name | Used by |
|---|---|---|
| repository variable | `ARTIFACTORY_URL` (ends in `/artifactory`), `ARTIFACTORY_OPA_BUNDLES_REPOSITORY` (`opa-bundles`) | B, C, D, E |
| repository variable | `JFROG_PLATFORM_URL`, `JFROG_OIDC_PROVIDER_NAME`, `JFROG_OIDC_AUDIENCE` | B, C, D, E (OIDC token first; fallback tokens below) |
| repository variable | `OPA_BUNDLE_SIGNING_KEY_ID` (default `governance-policies`), `OPA_BUNDLE_SIGNING_PUBLIC_KEY` (PEM) | B (id in the claims), C, D, E (verification) |
| repository variable | `OPA_BUNDLE_SIGNING_KEY_ID_PREVIOUS`, `OPA_BUNDLE_SIGNING_PUBLIC_KEY_PREVIOUS` | C, D, E during a key rotation |
| repository variable | `APP_REPOSITORY` (`ORG/policy_kafka`) | C, E |
| repository secret | `GITLEAKS_LICENSE` (organisation accounts only) | A |
| environment `artifactory-publish` | secret `OPA_BUNDLE_SIGNING_KEY` (private PEM), secret `ARTIFACTORY_PUBLISH_TOKEN` (fallback) | B |
| environments `promote-<env>` | secrets `APP_REPO_TOKEN`, `ARTIFACTORY_PROMOTE_TOKEN` (fallback); required reviewers for staging and production | C |
| environments `rollback-<env>` | same secrets; required reviewers | E |
| environments `verify-<env>` | variables `OPENSHIFT_SERVER`, `OPENSHIFT_NAMESPACE`; secrets `OPENSHIFT_TOKEN`, `ARTIFACTORY_VERIFY_TOKEN` (fallback), `SLACK_WEBHOOK_URL` (optional) | D |

Everything in this table is an external input (plan assumptions 1, 2, 5, 6). The application repository adds
`POLICIES_REPOSITORY` and `POLICIES_DISPATCH_TOKEN` so that its `deploy.yml` can start Workflow D.

## A. `validate.yml` (pull requests)

Runs `scripts/validate.sh` step by step: `opa fmt --diff --fail`, `opa check --strict`, `opa test` with all six
bundles, coverage threshold 85 %, roots and imports, lib copies, forbidden builtins, contract sync, fixtures against
expected decisions, inputs/outputs/catalogue against the schemas, Regal, gitleaks, `opa build` dry run per bundle. A
report artifact is uploaded. No secrets, read-only permissions, pinned OPA (`OPA_VERSION` + `opa.sha256`).

Fails because of: a formatting difference (run `scripts/opa.sh fmt --write bundles tests`), a failing test, coverage
below the threshold, a package outside its root, a cross-bundle import, a lib copy that drifted, a fixture whose
expected decision changed (review, then `scripts/eval-fixtures.sh --update`), a schema violation, a Regal finding, a
secret-looking string. `tests/integration/validate-negative.sh` demonstrates each rejection.

## B. `publish.yml` (push to `main` touching `bundles/**`, `OPA_VERSION`, `opa.sha256`; or manual with a bundle list)

1. Finds the previous candidate (latest file under `releases/candidates/`) and the bundles changed since its `gitSha`
   (`scripts/changed-bundles.sh`); a manual run names the bundles (`all` rebuilds everything).
2. `scripts/build-release.sh`: revision `<bundle>-<yyyyMMdd>.<run_number>-<short sha>`, `.manifest.metadata`
   (`git_sha`, `git_ref`, `built_at`, `contract_version`, `opa_version`, `signing_key_id`), `opa build -O1` with
   entrypoints and a per-bundle partial namespace, RS256 signature with a claims file carrying the key id, `.sha256`,
   extracted `signatures.json`.
3. `scripts/release-manifest.sh`: unchanged bundles keep their previous revision (and key id); `releaseId` is the hash
   of the sorted revision set, identical to Governance's `PolicyRelease.of`.
4. `scripts/assemble-release.sh` downloads the unchanged revisions back and checks their checksums.
5. `tests/integration/smoke.sh`: `opa run --server` with signature verification (key id taken from the manifest),
   `ready == true`, loaded revisions equal to the manifest, every fixture equal to its expected decision.
6. `scripts/publish.sh`: HEAD pre-check of every target path, upload with `X-Checksum-Sha256`, properties
   (`git.sha`, `contract.version`, `opa.version`, `release.id`), verification by download, candidate manifest.
7. GitHub Release draft with the manifest, checksums and signatures.

Fails because of: no bundle changed (a notice, not an error), a smoke failure (the fixtures disagree with the signed
artifacts, or the candidate **mixes signing key ids** after a rotation: run the workflow manually with `bundles: all`),
an existing target path (someone published that revision already: never force it, bump the run), Artifactory
authentication (OIDC provider or fallback token). Re-run after fixing; every run produces new revisions, nothing is
overwritten.

## C. `promote.yml` (manual: `releaseId`, `environment`, `source`, `reason`)

`scripts/promote.sh`: downloads the source manifest (`releases/candidates/<id>.json` or the source environment's
record), re-verifies every artifact (`scripts/verify-artifacts.sh`: HEAD, download, checksum, signature and fixture
smoke; no rebuild), writes the promotion record and event, renders the application release file, and
`scripts/app-repo-pr.sh` opens the pull request on `deploy/helm/governance/releases/<env>.json` (branch
`policy-release/<env>/<releaseId>`, auto-merge for dev only). The environments `promote-staging` and
`promote-production` carry the required reviewers.

Rules enforced: the release must exist in the source (`staging` from `dev` refuses a release that never reached dev);
the same release cannot be promoted twice to one environment; nothing is copied or rebuilt. Fails because of: a
missing or altered artifact (checksum), a signature that no trusted key verifies (`OPA_BUNDLE_SIGNING_PUBLIC_KEY*`),
missing `APP_REPO_TOKEN` scopes (`contents: write`, `pull_requests: write`). Merging the PR triggers `deploy.yml` in
policy_kafka, which rolls the pods and then starts Workflow D.

## D. `verify.yml` (from `deploy.yml`, manual, nightly for every environment)

`scripts/verify-active.sh` against the namespace: every governance pod reports the expected release through its
readiness probe (`policyRelease` details: id, match, ready, per-bundle revisions), every sidecar has all bundles
active, every fixture posted to each sidecar's loopback Data API through `oc exec` equals its expected decision; the
pods must agree on one release id (printed when none was given). Then `scripts/verify-artifacts.sh` against
Artifactory (artifacts exist unchanged, load under signature verification). A pass writes
`releases/<env>/verified/<releaseId>.json` (`scripts/mark-verified.sh`, once) and an event; a Slack message is sent
when `SLACK_WEBHOOK_URL` is set.

Fails because of: pods disagreeing on the release (a rollout in progress: re-run later), a pod not ready (runbook: "A
promotion that never becomes ready"), a fixture whose live decision differs from `expected/` (the artifacts do not
match the sources that produced the fixtures: compare the release id with the candidate's), a missing artifact (runbook:
"Bundle outage"). Only verified releases are rollback targets, so a persistent D failure must be fixed or rolled
back, not ignored.

## E. `rollback.yml` (manual: `environment`, `targetReleaseId`, required `reason`)

`scripts/promote.sh --rollback`: the target must have a promotion record **and** a verified marker in the environment,
its artifacts are re-verified, a rollback event is written, the application release file is rendered and a pull
request is opened (never auto-merged). The source manifest is read from the previous stage by convention
(`candidates` for dev, `dev` for staging, `staging` for production). No artifact is touched. The immediate cluster-side
alternative (`helm rollback`) and why Workflow E must still run afterwards are in the runbook.

## Common tasks

| Task | How |
|---|---|
| Promote the latest candidate to dev | Workflow C with the id from the Workflow B summary (or the GitHub Release draft), `source: candidates` |
| Promote dev to staging, staging to production | Workflow C with `source: dev` (or `staging`); reviewers approve the environment |
| Verify an environment now | Workflow D, `environment` only (the pods tell the release id) |
| Roll back | Workflow E with a verified target; merge its PR; or `helm rollback` first (runbook) |
| Find out what runs where | intended: `deploy/helm/governance/releases/<env>.json`; actual: readiness probe or `GET /api/v1/admin/policy-releases/current` (auditor); history: `releases/<env>/events/` |
| Rebuild everything (OPA upgrade, key rotation) | Workflow B manually with `bundles: all` and a reason; `OPA_VERSION` changes rebuild every bundle automatically |
| Prove the flow without Artifactory | `tests/integration/publish-local.sh` (B, including a key rotation), `tests/integration/promote-local.sh` (C, D's artifact half, E) against the nginx stand-in |

## Security properties the workflows rely on

Pinned OPA by digest, actions pinned by commit SHA, least-privilege `permissions`, OIDC tokens minted per run with
scoped expiring tokens as the only fallback, no credential or key material in the repository (gitleaks in A and in the
application CI), signatures verified before any promotion and by every sidecar, immutability enforced both by the
HEAD pre-check and by the repository permission, protected environments for staging and production, `CODEOWNERS` review
per bundle.
