# Extraction checklist: `policies/` to the `governance-policies` repository

The directory is already laid out as the repository root (workflows under `.github/`, scripts with repository-relative
paths, its own pins and configuration). The extraction itself is a Git operation plus the organisation-side inputs
that no checkout can provide. Tick the items in order; every item marked **[A]** is an external input.

## 0. Before splitting

- [ ] `scripts/validate.sh`, `tests/integration/validate-negative.sh`, `publish-local.sh`, `promote-local.sh` green on `main`.
- [ ] Nothing uncommitted under `policies/`; the policy_kafka IT and Compose stack pass with the current `dist/`.
- [ ] Decide who owns which bundle and replace the `@ORG/...` placeholders in `.github/CODEOWNERS` with real teams **[A]**.
- [ ] Decide the GitHub organisation and repository name (`ORG/governance-policies`) **[A]**.

## 1. Split and push

```
cd policy_kafka
git subtree split --prefix=policies -b governance-policies-main        # keeps the history of policies/
gh repo create ORG/governance-policies --private --description "OPA policy bundles of the Kafka governance platform"
git push git@github.com:ORG/governance-policies.git governance-policies-main:main
```

- [ ] In the new repository: `README.md`, `OPA_VERSION`, `opa.sha256`, `REGAL_VERSION`, `.regal/`, `.gitleaks.toml`,
      `.github/workflows/{validate,publish,promote,verify,rollback}.yml`, `.github/CODEOWNERS`, `bundles/`, `contracts/`,
      `tests/`, `scripts/`, `docs/` are at the root. `build.sh` and `test.sh` (the policy_kafka wrappers) may stay; they
      are harmless.
- [ ] `scripts/check-contract-sync.sh` is a no-op there (no `../contracts/opa`); keep it until step 4.

## 2. Branch protection on `main`

- [ ] Require a pull request, at least one approval, **review from code owners**, dismiss stale approvals.
- [ ] Required status check: `validate / policies` (Workflow A's job).
- [ ] Linear history, no force pushes, no deletions; restrict who can push to `main` to the platform team.
- [ ] Allow auto-merge (Workflow C's dev PRs live in the application repository, not here, but the setting does no harm).

## 3. Environments and variables (names only; see `docs/operations.md` for who uses what)

- [ ] Repository variables: `ARTIFACTORY_URL`, `ARTIFACTORY_OPA_BUNDLES_REPOSITORY`, `JFROG_PLATFORM_URL`,
      `JFROG_OIDC_PROVIDER_NAME`, `JFROG_OIDC_AUDIENCE`, `OPA_BUNDLE_SIGNING_KEY_ID`, `OPA_BUNDLE_SIGNING_PUBLIC_KEY`,
      `APP_REPOSITORY` **[A]**; the `_PREVIOUS` pair only during a rotation.
- [ ] Repository secret `GITLEAKS_LICENSE` for organisation accounts **[A]**.
- [ ] Environment `artifactory-publish`: secret `OPA_BUNDLE_SIGNING_KEY` (generated and kept per the key custody rule
      **[A]**, plan assumption 6), fallback secret `ARTIFACTORY_PUBLISH_TOKEN` only when no OIDC provider exists.
- [ ] Environments `promote-dev`, `promote-staging`, `promote-production`, `rollback-dev`, `rollback-staging`,
      `rollback-production`: secret `APP_REPO_TOKEN` (fine-grained token on `ORG/policy_kafka` with `contents: write`
      and `pull_requests: write`), fallback `ARTIFACTORY_PROMOTE_TOKEN`; **required reviewers** on every staging and
      production environment **[A]**.
- [ ] Environments `verify-dev`, `verify-staging`, `verify-production`: variables `OPENSHIFT_SERVER`,
      `OPENSHIFT_NAMESPACE`, secrets `OPENSHIFT_TOKEN` (ServiceAccount allowed to `get pods` and `exec` into the
      governance pods of that namespace), `ARTIFACTORY_VERIFY_TOKEN` (fallback), `SLACK_WEBHOOK_URL` (optional) **[A]**.

## 4. Artifactory **[A]**

- [ ] Generic repository `opa-bundles`; the CI identity has **deploy without overwrite** (no delete, no overwrite) on
      the whole repository; a retention rule that never removes anything referenced by `releases/**`.
- [ ] JFrog OIDC provider for the GitHub organisation (`JFROG_OIDC_PROVIDER_NAME`, audience) with identity mappings
      for the publish and promotion environments; otherwise scoped expiring tokens in the environment secrets.
- [ ] A read-only token for `opa-bundles/**` per OpenShift environment, delivered to the `artifactory-opa-reader`
      Secret (External Secrets or the platform's secret sync).
- [ ] Published contracts: `opa-contracts/<version>/input.schema.json`, `output.schema.json` from this repository's
      `contracts/`; Governance then pins the version in `pom.xml` and the contracts jar stops carrying a hand copy.

## 5. Application repository (policy_kafka)

- [ ] Variables `POLICIES_REPOSITORY` (`ORG/governance-policies`) and secret `POLICIES_DISPATCH_TOKEN`
      (`actions: write` on the policy repository) so that `deploy.yml` starts Workflow D.
- [ ] Decide how policy_kafka keeps the bundles its IT and Compose stack need after the split. Recommended: keep
      `policies/` as a **read-only mirror** refreshed with `git subtree pull --prefix=policies <url> main --squash`
      at each policy release, so `PlatformContainers` and `deploy/scripts/upload-release.sh` stay hermetic and
      `check-contract-sync.sh` keeps guarding the schema copy. Alternatives: a submodule pinned to a tag, or an IT that
      downloads the signed revisions of `releases/dev.json` from Artifactory (needs network and the read token).
- [ ] `ci.yml`'s "policies" job then runs `scripts/validate.sh` on the mirror only as a drift check, or is removed.
- [ ] Decide where `docs/policy-inventory.html` is generated from: `scripts/inventory.sh --out <path> --release-files <dir>`
      can write it into the mirror (it needs the release files of policy_kafka for the per-environment revisions); the
      `--check` step is a no-op in the policy repository itself because no `../docs` exists there.
- [ ] Branch protection on `deploy/helm/governance/releases/*.json`: required checks (`helm` render check), reviewers
      for staging and production files (`CODEOWNERS` on `deploy/helm/**`), auto-merge allowed for Workflow C's dev PRs.

## 6. First publish and promotion

- [ ] Run Workflow B manually with `bundles: all` and the reason "initial publish": the first candidate.
- [ ] Workflow C: promote it to dev (`source: candidates`); the PR replaces the seeded
      `deploy/helm/governance/releases/dev.json` (local build revisions) with real revisions and key ids.
- [ ] `deploy.yml` rolls dev; Workflow D passes and writes the first verified marker.
- [ ] Repeat for staging and production through the protected environments.
- [ ] Remove the seeded release files' `source: promotion` placeholders only by promoting (never hand-edit a release file).

## 7. After the extraction

- [ ] Update `policies/README.md` ownership table and `docs/governance.md` in policy_kafka (the directory is now a mirror).
- [ ] Record the repository URL, the Artifactory URL and the signing key ids in the plan's assumption list.
- [ ] Schedule the Regal and OPA upgrades (`REGAL_VERSION`, `OPA_VERSION` + `opa.sha256`) as ordinary pull requests;
      an OPA bump rebuilds every bundle on the next Workflow B run.
