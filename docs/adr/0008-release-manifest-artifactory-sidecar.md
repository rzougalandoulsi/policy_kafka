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
