# governance-policies

The Rego policies of the Kafka governance platform: six independently versioned OPA bundles composed by `core` into one
decision (`data.governance.core.decision`), the JSON contract they share with the Governance service, the fixtures that
pin their behaviour, and the tooling that validates, builds, signs and publishes them.

This directory is laid out as its own repository (`git subtree split --prefix=policies`) and is still built inside
`policy_kafka` during the transition; see "Ownership" below for what moves with it and what stays.

## Layout

```
bundles/<name>/        .manifest (root governance/<name>), *.rego, identical lib.rego per bundle   (core manifests naming quotas resources changes)
contracts/             input.schema.json  output.schema.json  catalog.schema.json  release-manifest.schema.json  CHANGELOG.md
tests/*_test.rego      Rego unit tests over all six bundles (78)
tests/fixtures/        inputs/*.json (contract 2.0, schema-validated)  expected/*.json (decision per input, minus revisions)
tests/integration/     smoke.sh (opa run --server with signed artifacts), artifactory-standin/ (nginx), publish-local.sh, validate-negative.sh
scripts/               opa.sh install-opa.sh build.sh validate.sh changed-bundles.sh check-*.sh eval-fixtures.sh lint.sh
                       build-release.sh release-manifest.sh assemble-release.sh publish.sh
.github/workflows/     validate.yml (Workflow A)  publish.yml (Workflow B);  .github/CODEOWNERS per bundle
OPA_VERSION opa.sha256 REGAL_VERSION .regal/config.yaml .gitleaks.toml
build.sh test.sh       root wrappers kept for policy_kafka (PlatformContainers, upload-release.sh)
```

Every script runs OPA through `scripts/opa.sh`: the binary on `PATH` when its version equals `OPA_VERSION`, otherwise
the `openpolicyagent/opa:<OPA_VERSION>` image. No other OPA version is ever used.

## Day to day

| Task | Command |
|---|---|
| Everything Workflow A runs, locally | `scripts/validate.sh` (`SKIP_LINT=1`, `SKIP_SECRETS=1` when Docker is not available) |
| Unit tests only | `test.sh` |
| Local unsigned bundles + `dist/release.json` for the Compose stack and the Governance IT | `build.sh [revision]` |
| Add a behaviour fixture | drop `tests/fixtures/inputs/<name>.json`, run `scripts/eval-fixtures.sh --update`, review `expected/<name>.json`, commit both |
| Prove the gates reject broken trees | `tests/integration/validate-negative.sh` |
| Exercise Workflow B end to end against a local Artifactory stand-in | `tests/integration/publish-local.sh` |

Rules the checks enforce: exactly six bundles with roots `governance/<name>`, no root a prefix of another, every package
under its bundle root, no cross-bundle `import` (functions do not resolve across bundles; `core` composes by dynamic
reference), identical `lib.rego` copies, no `http.send` / `opa.runtime`, `opa fmt`, `opa check --strict`, 85 % test
coverage, fixtures valid against the input schema, decisions valid against the output schema, the merged catalogue
valid against `catalog.schema.json`, Regal clean, gitleaks clean, and `opa build` succeeding per bundle.

## Workflows

**A, `validate.yml`** runs on pull requests touching bundles, contracts, tests, scripts or the pins. Pinned OPA
(`OPA_VERSION` + `opa.sha256`), pinned actions by commit SHA, read-only permissions, no secrets. Make it a required status
check on `main` and enable code-owner reviews (`.github/CODEOWNERS`, teams are placeholders to fill in).

**B, `publish.yml`** runs on pushes to `main` that change a bundle (or on demand for a bundle list). For each changed
bundle since the previous candidate manifest: revision `<bundle>-<yyyyMMdd>.<run_number>-<short sha>`, `.manifest`
metadata (`git_sha`, `git_ref`, `built_at`, `contract_version`, `opa_version`), `opa build -O1` with explicit
entrypoints and a per-bundle partial namespace, RS256 signature with a claims file (`keyid: governance-policies`),
`sha256`, extracted `signatures.json`. Unchanged bundles keep their last published revision and are downloaded back and
checksummed. Smoke: `opa run --server` with signature verification, `ready == true`, revisions equal to the manifest,
every fixture equal to its expected decision. Publish: HEAD pre-check of every target path (any existing path fails the
run), upload to `<repo>/<bundle>/<revision>/`, properties `git.sha`, `contract.version`, `opa.version`, `release.id`,
verification by download, candidate manifest `releases/candidates/<releaseId>.json`, GitHub Release draft
`release/<releaseId>` with the manifest and checksums attached. Nothing is promoted by this workflow.

Inputs that live outside the repository (plan assumptions 1 and 6), all referenced by name only:

| Where | Name | Meaning |
|---|---|---|
| repository variable | `ARTIFACTORY_URL` | base URL ending in `/artifactory` |
| repository variable | `ARTIFACTORY_OPA_BUNDLES_REPOSITORY` | generic repository key (`opa-bundles`), deploy-without-overwrite for the CI identity |
| repository variable | `JFROG_PLATFORM_URL`, `JFROG_OIDC_PROVIDER_NAME`, `JFROG_OIDC_AUDIENCE` | JFrog OIDC integration for this GitHub organisation |
| environment `artifactory-publish` secret | `OPA_BUNDLE_SIGNING_KEY` | RS256 private key (PEM); the public key is derived in the job and shipped to OPA through the Helm chart |
| environment `artifactory-publish` secret | `ARTIFACTORY_PUBLISH_TOKEN` | fallback only: scoped, expiring token, used when no OIDC provider is configured |
| repository secret | `GITLEAKS_LICENSE` | only for organisation accounts |

## Release manifest

`releaseId = rel-<first 12 hex of sha256("core=<rev>,changes=<rev>,..." sorted by name)>`, the same formula as
Governance's `PolicyRelease.of`, so a set of revisions has one id wherever it is computed. Shape in
`contracts/release-manifest.schema.json`; candidates carry `url`, `gitSha`, `createdAt`, `previousReleaseId`.

## Ownership (contract between the two repositories)

| governance-policies | policy_kafka (Governance) |
|---|---|
| Rego, bundle roots, `contract_version`, tests, fixtures, catalogue schema, release manifests, Workflows A to E, Artifactory publishing and promotion | `OpaInputAssembler` for a contract major, decision parsing, `PolicyRelease` verification and readiness, team configuration, exceptions, usage, the OPA sidecar config template and the release ConfigMap (Helm chart), local-mode bundle serving |
| Shared: `contracts/*.schema.json`. Governance keeps a pinned copy under `contracts/opa/`; `scripts/check-contract-sync.sh` keeps both equal while they share one repository. After the extraction the schemas are published as a versioned artifact and Governance pins the version. A major bump ships Governance first (accepting both majors), then the domain bundles, then core. | |
