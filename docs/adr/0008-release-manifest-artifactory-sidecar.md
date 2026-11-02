# ADR 0008: The release manifest is the unit of promotion; OPA runs as a sidecar pulling pinned, signed revisions from Artifactory

## Context
ADR 0007 made the policy release (one revision per bundle) the unit Governance verifies. In local mode Governance
stores and serves the bundles itself. Deployed environments need an immutable, auditable distribution path that
no running component can alter, a sidecar topology that keeps the OPA Data API off the network, and a readiness gate
that never lets a replica with an incomplete set serve traffic (plan sections 10 to 12).

## Decision
- **Artifacts.** Workflow B of `governance-policies` builds each changed bundle as a signed (RS256, claims file,
  `keyid governance-policies`), optimized (`-O1`, per-bundle partial namespace), checksummed tarball with revision
  `<bundle>-<yyyyMMdd>.<run>-<short sha>` and uploads it to `opa-bundles/<bundle>/<revision>/` behind a HEAD
  pre-check; the repository denies overwrite server-side. A candidate release manifest (`releaseId`, revisions,
  checksums, urls) is written to `releases/candidates/`. Revisions are never modified or removed.
- **Promotion is a file.** `deploy/helm/governance/releases/<environment>.json` in policy_kafka holds the promoted
  release. The Helm chart renders it into the OPA sidecar configuration as exact resource paths (no mutable pointer in
  Artifactory) and mounts it for Governance. Changing the file changes a pod annotation checksum and rolls the pods.
  Workflow C writes it, Workflow E restores a previous verified one; both are Git changes with review.
- **Governance release mode.** `governance.opa.mode=release` (forced by the `openshift` Spring profile, which refuses
  `local`) replaces the database-backed release with `FilePolicyReleaseSource`: the manifest's revisions are the
  expected set, its id must equal the id computed from them, and every decision verifies OPA's provenance against it.
  Upload, activation and rollback answer 409 `POLICY_SOURCE_IS_GIT`; `/internal/opa/bundles/*` is 404; the admin
  console hides the controls (`LOCAL_POLICY_ADMIN=false`). `verifyActivation` auto-rollback exists only in local mode.
- **Sidecar.** OPA (`-static` image) runs in the Governance pod with `--addr 127.0.0.1:8181` and a diagnostic
  listener on 8282 for probes and metrics; readiness is `/health?bundles&plugins`, so the sidecar is ready only once
  all six bundles are active, and Governance's `policyRelease` indicator additionally requires exact revision equality
  and `data.governance.core.ready`. Signature verification is mandatory (`signing.keyid` per bundle, public key in the
  chart values, rotation by listing two keys). The token is a read-only scoped Secret; `persist: true` lets a pod
  restart on cached bundles while Artifactory is unavailable, still verified against the manifest.
- **Pod security and network.** restricted-v2 compatible pod (no `runAsUser`, read-only root filesystems, no
  capabilities, `emptyDir` for `/tmp` and `/var/opa`), NetworkPolicy admitting 8080 from the router and the admin
  console and 8080 + 8282 from monitoring only, explicit egress list; 8181 is reachable from nowhere.

## Consequences
- Every decision row names a release that exists as an immutable set of artifacts; audits follow `releaseId` from
  the decision to the Git promotion and to the Artifactory paths.
- A broken or incomplete promotion is visible as a pod that never becomes ready (`--wait --atomic` rolls the deploy
  back) rather than as mixed decisions.
- The plan's `release.maxAge` freshness check was not implemented: a persisted bundle that equals the promoted
  manifest is correct regardless of its build date, and an expired-by-age valid release would only produce outages.
- Local developer stacks keep the database path unchanged; the two modes share one verification code path.

## Update 2026-11-02 (Phases 5 and 8)
- **No `history.ndjson`.** The repository denies overwrite, so an append-only file is impossible. Promotion history is the set of immutable files `releases/<env>/<releaseId>.json` (record), `releases/<env>/events/<ts>-<id>-<kind>-<releaseId>.json` and `releases/<env>/verified/<releaseId>.json` (written once by Workflow D), ordered by their timestamps; the current selection is the release file in Git.
- **Signing key rotation is per bundle.** Workflow B writes the signing key id into the signature claims and `.manifest.metadata.signing_key_id`; `release-manifest.sh` copies it into each bundle entry (`keyId`, optional in `release-manifest.schema.json`); `promote.sh` carries it into the application release file; the chart renders `signing.keyid` per bundle from it (falling back to `signing.keyId` for entries without one) and fails `helm template` when a release names a key id that is neither `signing.keyId` nor in `signing.additionalKeys`. Workflows C, D and E verify with a directory of trusted public keys (`OPA_BUNDLE_SIGNING_PUBLIC_KEY` and the `_PREVIOUS` pair). Without this the single `signing.keyid` shared by all six bundles would have made a mixed-key release unverifiable and a rotation impossible without downtime. The smoke still verifies with one key per manifest, so a candidate mixing key ids is refused and a rotation starts with a full publish (`bundles: all`). Procedure: `docs/runbook.md`, "Signing key rotation"; proof: `publish-local.sh` step 5 and the rotation case of `render-check.sh`.
- **Persistence scope.** `/var/opa` is an `emptyDir`: persisted bundles survive a container restart inside the pod, not a pod re-creation. A new pod while Artifactory is unreachable stays not ready and receives no traffic; the rollout holds and `helm --atomic` rolls the deploy back. This is the intended behaviour and is why the readiness gate, not an age limit, protects correctness.
- **`release.maxAge` stays unimplemented** (decision above): a persisted bundle equal to the promoted manifest is correct whatever its age; an age check would only turn an Artifactory outage into a policy outage.
- Cluster-side verification (Workflow D) runs through `oc exec` into the governance container and reads the unauthenticated readiness probe, because the sidecar's Data API binds loopback and the admin endpoint needs an auditor token.
