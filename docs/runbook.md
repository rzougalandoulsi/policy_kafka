# Runbook

The single operational entry point. Deeper material it points to: `policies/docs/operations.md` (Workflows A to E
step by step, Artifactory layout, re-runs), `deploy/helm/governance/README.md` (chart values, Secret contract),
`docs/adr/0007` to `0010` (why things are the way they are), `docs/admin-ui.md` (console troubleshooting).

## Health
- `GET /actuator/health/readiness` (components `db`, `opa`, `policyRelease`); `GET /actuator/health/liveness`.
- `policyRelease` is UP only when OPA runs exactly the expected release (`releaseId`, `match`, `ready`, per-bundle
  expected versus loaded revision in the details). In release mode the expected release is the mounted
  `/etc/governance/release.json`; in local mode it is the ACTIVE set in the database.
- OPA sidecar (release mode): `GET :8282/health` (liveness), `GET :8282/health?bundles&plugins` (readiness: every
  configured bundle downloaded, verified and activated), `GET :8282/metrics`.

## Key metrics (Prometheus, `/actuator/prometheus`)
| Metric | Meaning / alert |
|---|---|
| `governance_opa_evaluation_seconds{stage,outcome}` | OPA latency; `outcome=ERROR` rising ⇒ OPA or bundle problem |
| `governance_decisions_total{stage,type}` | decision mix |
| `governance_policy_revision_match` | 1 OK, 0 OPA runs a different revision than ACTIVE, -1 unknown |
| `governance_outbox_pending` / `governance_outbox_failed` | Kafka publishing backlog / permanently failed commands (alert on failed > 0) |
| `governance_inbox_retry` / `governance_kafka_deadletters_open` | events awaiting retry / dead letters (alert on open > 0) |
| `governance_approvals_pending` | human backlog |
| `governance_shadow_findings_total{policyId,kind}` | what a shadow-mode policy would have denied / sent to approval; the soak evidence before flipping it to enforce |
| `governance_usage_snapshots_total{outcome}` / `governance_usage_stale_total` | confirmed-state records consumed from `teams.repo.manifest` / stage 2 evaluations deferred because the snapshot lagged the plan (alert when `stale` keeps rising: the usage consumer is behind or the topic is unreadable) |
| `governance_quota_reservations_open`, `governance_quota_reservations_total{outcome}` | capacity held by authorised plans still in flight; `EXPIRED` rising means plans are authorised but never reported back |
| `kafka_consumer_fetch_manager_records_lag_max` | consumer lag on `response.manifest` |

## Diagnosing a request
1. `GET /api/v1/requests/{id}` → status, `failureReason`, history with event ids.
2. `GET /api/v1/requests/{id}/decisions` → every OPA decision with `policyRevision`, `configurationVersion`, violations, applied exceptions.
3. `GET /api/v1/admin/audit?requestId={id}`.
4. Logs carry `requestId`/`planId` (MDC) and `X-Correlation-Id` for HTTP calls.

## Failure modes
| Symptom | Cause | Action |
|---|---|---|
| 503 `POLICY_EVALUATION_UNAVAILABLE` | OPA down, no active release (local: nothing ACTIVE; release mode: release file missing or its id does not match its revisions), revision mismatch, `ready == false` | `GET /api/v1/admin/policy-releases/current` (`mode`, `source`, per-bundle match), OPA logs; clients retry with the same `Idempotency-Key`; release mode: see "Release mode operations" |
| Request stuck in `SUBMITTED` | Orchestrator (or stand-in) down / outbox not relayed | `governance_outbox_pending`, Orchestrator logs |
| Request `EVALUATION_ERROR` after PLAN_PROPOSED | stage 2 OPA failure; inbox retries (`max-evaluation-attempts`) then REJECT_PLAN is sent | fix OPA; resubmit |
| `STALE_PLAN` | confirmed state moved while an approval was pending | resubmit the manifest (replan) |
| Stage 2 `EVALUATION_ERROR` "Usage snapshot ... older than the plan" | Governance has not consumed the confirmed state the plan was computed against (`teams.repo.manifest` lag, missing read ACL, consumer disabled) | check the usage consumer logs/lag, `governance_usage_snapshots_total`; the inbox retries by itself; `GOVERNANCE_USAGE_REQUIRE_FRESH_SNAPSHOT=false` disables the guard (quotas may then undercount) |
| `TEAM_QUOTA_EXCEEDED` although the team "has room" | reservations of in-flight plans count; or confirmed usage includes another repository of the team | `GET /api/v1/admin/teams/{id}/usage` (capacity per environment, repositories, open reservations) |
| 409 `TEAM_CONFIGURATION_EXTERNAL` on a team edit | the team is owned by an external registry | edit the registry, or save with `source=governance` to take ownership (audited) |
| Dead letters | poison record or handler bug | `GET /api/v1/admin/dead-letters`, fix, `POST /api/v1/admin/dead-letters/{id}/resolve` |
| Bundle `FAILED` (local mode) | OPA could not load it; previous version restored automatically by `verifyActivation` | fix the bundle, upload a new revision. Release mode has no automatic rollback: see "A promotion that never becomes ready" |

## Policy rollout
Two modes (ADR 0008). **Deployed environments run in `release` mode**: the policy set is pinned by
`deploy/helm/governance/releases/<env>.json`, OPA pulls the signed revisions from Artifactory, upload/activate/rollback
answer 409 `POLICY_SOURCE_IS_GIT`, and the console shows the release read-only. Promote with Workflow C (`promote.yml`: verifies the artifacts again, writes the
immutable record `releases/<env>/<releaseId>.json` in Artifactory, opens the release PR), verify with Workflow D
(`verify.yml`: artifacts, every pod's readiness release, sidecar health, fixtures on each sidecar; writes
`releases/<env>/verified/<releaseId>.json`), roll back with Workflow E (`rollback.yml`: only a previously verified
release, rollback event, release PR with the reason); `helm upgrade` rolls the pods and each pod is ready only when its sidecar runs the whole set
(`GET /api/v1/admin/policy-releases/current` shows `mode`, `source`, expected versus loaded per bundle).
`deploy/scripts/post-deploy-check.sh` runs the per-pod check after a deploy. A pod that never becomes ready: `oc logs
<pod> -c opa` (download, signature or activation error), then the readiness details. **Local mode** (Compose, IT):
`policies/build.sh <revision>` builds six bundles and `dist/release.json` → upload each (`POST /api/v1/admin/policy-bundles`, bundle + shared test report) → `POST /api/v1/admin/policy-releases {versionIds}` activates the set atomically (`deploy/scripts/upload-release.sh` does all of it) → watch `GET /api/v1/admin/policy-releases/current` until `match` and `ready` → `POST /api/v1/admin/policy-bundles/rollback?bundle=<name>` restores one bundle if needed. Readiness (`/actuator/health/readiness`, component `policyRelease`) is DOWN while OPA runs anything but the active release, so a replica with a stale bundle receives no traffic. Configuration changes: create a draft, activate, rollback.
The same steps are available in the admin console (`/policy-bundles` upload + activate with a live adoption watcher, `/policy-configurations` with a diff before activation). See `docs/admin-ui.md`.

## Team configuration and shadow mode
`PUT /api/v1/admin/teams/{teamId}` (or the console's Teams page) sets authorised prefixes, quotas and role overrides; it applies to the next evaluation. To soak a policy: create a policy configuration version with `enforcement: {"POLICY_ID": "shadow"}`, activate it, watch `GET /api/v1/admin/shadow-findings?policyId=POLICY_ID` and the metric, then create a version without the entry to enforce.

## Admin console
| Symptom | Cause | Action |
|---|---|---|
| Sign-in fails / loops | `OIDC_ISSUER` differs from the `iss` the provider mints, or the `/oidc/` proxy does not force the issuer Host | `docs/admin-ui.md` → Troubleshooting |
| Signed in, every call 401 | Governance validates another issuer than the token | compare `OAUTH2_ISSUER_URI` with the token in the account menu |
| "No governance role" | token lacks `governance-admin` / `governance-auditor` | identity provider role mapping, `ROLES_CLAIM` |
| Exception form falls back to free text | catalogue 503: OPA down or no active release | diagnostics banner on `/policy-bundles` |
| Every submission answers 503, readiness DOWN, `policy-releases/current` shows one bundle `match: false` | OPA refused that bundle revision (compile error, wrong root) or has not polled yet | local mode: wait one poll interval, then `rollback?bundle=<name>` (`verifyActivation` does it automatically after 90 s); release mode: the pod never became ready, see "Release mode operations" |

## Release mode operations (OpenShift, ADR 0008)

Vocabulary. The **release file** is `deploy/helm/governance/releases/<env>.json` in policy_kafka: the promoted set
(`releaseId`, six revisions with checksums and, since Phase 8, the `keyId` each bundle was signed under). The chart
renders it into the sidecar's `opa.yaml` (one pinned `resource` path and one `signing.keyid` per bundle) and mounts it
for Governance at `/etc/governance/release.json`. A pod is ready only when the sidecar reports every bundle active
**and** Governance's `policyRelease` indicator sees exactly those revisions with `data.governance.core.ready == true`.
The commands used below:

```
oc get pods -l app.kubernetes.io/name=governance -n <ns>                       # READY 2/2 is a serving pod
oc logs <pod> -c opa -n <ns> [--previous]                                       # download, signature, activation errors
oc exec <pod> -c governance -n <ns> -- curl -s localhost:8080/actuator/health/readiness   # policyRelease details
oc exec <pod> -c opa -n <ns> -- wget -qO- 'http://127.0.0.1:8282/health?bundles&plugins'  # sidecar readiness
deploy/scripts/post-deploy-check.sh --base-url <route> --environment <env> --namespace <ns>   # the whole check
```

### Bundle outage: Artifactory unreachable
| Situation | What happens | What to do |
|---|---|---|
| A **new pod starts** (rollout, scale-up, node drain) while Artifactory is unreachable | `/var/opa` is an `emptyDir`, so a new pod has nothing persisted. The sidecar retries the download with its polling backoff (60 to 120 s), its readiness keeps failing, `policyRelease` stays DOWN, the pod receives no traffic. A rollout holds (`maxUnavailable 0`, the old pods keep serving); a deploy through `deploy.yml` is rolled back by `helm --atomic` after its timeout. | Nothing to change on the policy side. `oc logs <pod> -c opa`: 401/403 is the `artifactory-opa-reader` token (expired, rotated, wrong scope), 404 is a revision that no longer exists (see the last row), connection refused or timeout is the egress NetworkPolicy, DNS, proxy or Artifactory itself. Fix the cause; the sidecar recovers on its next poll without a restart. Do not restart the ready pods meanwhile. |
| The **OPA container restarts inside a running pod** (liveness, OOM) | The `emptyDir` survives a container restart: OPA activates the persisted bundles from `/var/opa` (`persist: true`) and becomes ready before Artifactory answers. Governance still compares the loaded revisions with the mounted release file, so a persisted bundle that no longer matches the release keeps the pod not ready. There is no age limit on a persisted bundle (ADR 0008). | `oc logs <pod> -c opa --previous` for the reason (memory limit), then the readiness details. |
| Artifactory becomes unreachable **while pods run** | A failed poll keeps the active bundles; OPA logs the error (bundle plugin status) and keeps retrying; Governance stays ready and decisions continue with the active release. | Nothing degrades until a pod is re-created. Fix Artifactory before any rollout; postpone deploys (they would hold and be rolled back). Alert on the OPA log errors or the sidecar metrics on `:8282/metrics`. |
| A **pinned revision disappears** from Artifactory (retention rule, manual removal: HEAD 404) | Running pods keep serving; every new pod stays not ready. Workflow D's artifact check fails for the environment. | Revisions referenced by any promotion record are never to be removed (ADR 0008). Restore from the Artifactory backup, or roll back to a release whose six artifacts exist (Workflow E re-verifies them first). |

### Signature verification failure
Symptom: the sidecar log shows the download succeeding and the activation failing with a verification error for a
bundle; `/health?bundles` stays failing for that bundle; the pod never becomes ready. Causes, most likely first:
1. The key id the bundle was signed under is not in the chart's `keys` (`signing.keyId`/`signing.publicKey` plus
   `signing.additionalKeys`). Since Phase 8 the release file carries the `keyId` per bundle and `helm template` fails
   with "signed with key id ... neither signing.keyId nor listed under signing.additionalKeys", so this is caught in
   the render check and in `deploy.yml` before any pod rolls.
2. The public key under that id is not the key that signed the bundle: a wrong PEM pasted into the values file, or
   Workflow B switched to a new key without the chart change (see "Signing key rotation").
3. A tampered or truncated artifact. Workflow C and D compare the checksum before the signature, so this would have
   failed there first; treat a signature failure on an artifact whose checksum matches as case 1 or 2.

Checks: the key id is in the `keyid` claim of `<revision>.signatures.json` (attached to the GitHub Release draft of the
candidate, or `tar -xzf <bundle>.tar.gz -O .signatures.json`) and in the release file entry. Compare fingerprints:
Workflow B prints `signing key id ..., fingerprint ...` in its summary; on the chart value run
`openssl pkey -pubin -in key.pub -outform DER | sha256sum | cut -c1-16`. Reproduce locally with
`policies/scripts/verify-artifacts.sh --manifest <record> --verification-keys <dir with <keyId>.pem>`. Never work
around it: no `allow_insecure_tls`, no bundle without `signing`, no unsigned bundle in release mode.

### A promotion that never becomes ready
1. `oc rollout status` holds and `oc get pods` shows the new pods `1/2` or `0/2` while the old ones still serve. A deploy
   run through `deploy.yml` fails after `helm --atomic` rolled it back (10 minutes); Workflow D is then not reached.
2. Find the failing container. `oc describe pod` lists the failing probe. `oc logs <pod> -c opa` tells download,
   signature or activation (compile) errors. The Governance readiness details tell the rest: `releaseId: none` means the
   release file is unreadable or declares an id that does not match its revisions; `match: false` names the bundle whose
   loaded revision differs from the expected one; `ready: false` with `match: true` means core refuses the set (a
   bundle's `contract_version` major differs from core's).
3. Causes, in the order they are normally caught: the release file is malformed or names an untrusted key id (the
   render check and `helm template` fail, nothing rolls); a revision was never published or was removed (404 in the
   sidecar log); a signature failure (above); an incompatible contract major (Workflow A's composition tests prevent it
   for candidates built from one commit, so this points at a hand-edited release file).
4. Recovery. There is no fix-forward inside the cluster. Either repair the input (token, egress, key) and let the sidecar
   recover by itself, or revert the release: `helm rollback` restores the previous release file and OPA configuration
   immediately (next section), then Workflow E records the rollback and points the release file in Git back, otherwise
   the next `deploy.yml` run re-applies the broken file. Finish with Workflow D or `post-deploy-check.sh`.

### Rollback procedure
Policy rollback (release file) and application rollback (image) are independent; roll back only what broke.
1. Pick the target: a release that was promoted to this environment **and** verified by Workflow D, that is both
   `releases/<env>/<id>.json` and `releases/<env>/verified/<id>.json` exist in Artifactory. List candidates with the
   storage API (`GET <ARTIFACTORY_URL>/api/storage/<repo>/releases/<env>/verified?list&deep=0`) or read the events under
   `releases/<env>/events/`. Workflow E refuses anything else.
2. Run Workflow E (`rollback.yml` in the policy repository) with `environment`, `targetReleaseId` and a `reason`. It
   re-verifies the six artifacts (existence, checksum, signature, fixtures), writes
   `releases/<env>/events/<ts>-rollback-<id>.json` and opens a pull request on
   `deploy/helm/governance/releases/<env>.json` in policy_kafka. Rollback PRs are never auto-merged.
3. Review and merge the PR. `deploy.yml` rolls the pods (readiness-gated, `maxUnavailable 0`) and hands over to
   Workflow D, which verifies the environment and re-marks the release.
4. Incident shortcut when the PR path is too slow: `helm history governance -n <ns>` then
   `helm rollback governance <revision> -n <ns> --wait`. Helm restores the previous rendered ConfigMaps (release file
   and OPA configuration) at once; the pods roll back under the same readiness gate. Still run Workflow E afterwards:
   until its PR merges, Git says one release and the cluster runs another, and any `deploy.yml` run re-applies Git.
5. Application rollback: `helm rollback` to the previous Helm revision, or run `deploy.yml` with `image_tag` set to the
   previous sha. The release file is untouched by either.

### Signing key rotation
Where the keys live. Workflow B signs with the private key in the `artifactory-publish` environment secret
`OPA_BUNDLE_SIGNING_KEY` under the id `OPA_BUNDLE_SIGNING_KEY_ID` (default `governance-policies`); the id is written
into the signature claims and copied into every release manifest entry (`keyId`). Workflows C, D and E verify with
the public keys in the repository variables `OPA_BUNDLE_SIGNING_PUBLIC_KEY` (`OPA_BUNDLE_SIGNING_KEY_ID`) and, during a
rotation, `OPA_BUNDLE_SIGNING_PUBLIC_KEY_PREVIOUS` (`OPA_BUNDLE_SIGNING_KEY_ID_PREVIOUS`). The chart trusts
`signing.keyId`/`signing.publicKey` plus every entry of `signing.additionalKeys`, and the sidecar verifies each bundle
with the key id its release entry names. Only public keys are ever in Git. A rotation is therefore "two keys trusted,
then one":
1. **Generate** the new pair where the private key is to be kept (plan assumption 6):
   `openssl genrsa -out new.pem 3072 && openssl rsa -in new.pem -pubout -out new.pub`. Choose the new id, for example
   `governance-policies-2027`, and record the fingerprint (`openssl pkey -pubin -in new.pub -outform DER | sha256sum`).
2. **Trust the new key everywhere before anything is signed with it.** Chart: add
   `signing.additionalKeys.<new id>: <new.pub>` to `values-dev.yaml`, `values-staging.yaml` and
   `values-production.yaml`, run `deploy/helm/scripts/render-check.sh`, merge, deploy each environment (the pods roll,
   behaviour is unchanged because every release still names the old id). Policy repository variables: copy the current
   public key and id into `OPA_BUNDLE_SIGNING_PUBLIC_KEY_PREVIOUS` / `OPA_BUNDLE_SIGNING_KEY_ID_PREVIOUS`, then set
   `OPA_BUNDLE_SIGNING_PUBLIC_KEY` to `new.pub` and `OPA_BUNDLE_SIGNING_KEY_ID` to the new id. Workflows C, D and E now
   accept both.
3. **Switch Workflow B.** Replace the environment secret `OPA_BUNDLE_SIGNING_KEY` with `new.pem` (the id variable
   already changed in step 2). Run `publish.yml` manually with `bundles: all` and a reason: the candidate has all six
   revisions signed under the new id. An incremental run before that full one fails on purpose: the smoke refuses a
   candidate that mixes key ids, because `opa run` verifies with one key. From now on every candidate carries the new id.
4. **Promote** that candidate through dev, staging and production with Workflows C and D as usual. Each environment's
   release file now names the new id per bundle, and `helm template` checks the id against the chart's keys.
5. **Retire the old key.** When no release file references the old id (`grep -l keyId deploy/helm/governance/releases/*.json`
   and look at the ids) and no rollback target signed with it is still wanted: move the chart to
   `signing.keyId: <new id>` / `signing.publicKey: new.pub`, remove the old entry from `additionalKeys`, clear the
   `_PREVIOUS` variables, destroy the old private key. Keep the old public key under `additionalKeys` (and in
   `_PREVIOUS`) for as long as a rollback to an old-key release must remain possible: Workflow E re-verifies with the
   trusted-keys directory. Record date, ids and fingerprints in the policy repository's `contracts/CHANGELOG.md` or
   release notes.
Proof of the mechanics without an Artifactory: `policies/tests/integration/publish-local.sh` step 5 and the rotation case
of `deploy/helm/scripts/render-check.sh`.

### Contract major bump (input/output contract N to N+1)
Core requires every bundle's `contract_version` major to equal its own, and a release is promoted as one set, so the
six bundles move together; "domain bundles first" is not an option here. Order:
1. **Governance first.** `OpaInputAssembler` emits N+1, `OpaInputContractTest` validates against the N+1 schema in
   `contracts/opa/`, `governance.opa.supported-contract-majors` lists N and N+1. Deploy to every environment; the running
   bundles still declare N and stay accepted.
2. **Policies second, in one release.** One pull request in the policy repository bumps `contract_version` in all six
   bundles, updates `contracts/*.schema.json` and `contracts/CHANGELOG.md`, adapts rules, tests and fixtures
   (`scripts/eval-fixtures.sh --update`, review the diff). Workflow A proves the composed set is `ready` and every
   fixture still evaluates; Workflow B publishes a candidate with six new revisions; Workflows C and D promote it
   environment by environment. Until the promotion reaches an environment, that environment keeps evaluating N.
3. **Governance last.** Once every environment runs an N+1 release, drop N from `supported-contract-majors` in a later
   Governance release. While the two code bases share one repository, `policies/scripts/check-contract-sync.sh` keeps
   the two schema copies identical; after the extraction Governance pins a published contracts version
   (`policies/docs/contract.md`).

## Security notes
- Local stack: OPA has no published port; its bundle endpoint on Governance is guarded by `OPA_BUNDLE_TOKEN` and must stay off the public network.
- Release mode: the OPA Data API binds `127.0.0.1:8181` inside the pod, the diagnostic listener on 8282 serves health and metrics only and is reachable from the monitoring namespace alone (NetworkPolicy); the sidecar reads Artifactory with a read-only token from a Secret; bundle signatures are verified against public keys that live in the chart values (private keys never reach Git: `gitleaks git` over the whole history is part of Phase 8's checks and of `ci.yml`).
- Kafka credentials per service (`deploy/kafka/acls.sh`): only `governance` can write `requests.manifest`.
- Secrets via environment (`deploy/.env.example`); use TLS listeners (`SASL_SSL`) and a real issuer in production.
