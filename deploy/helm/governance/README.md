# governance Helm chart

Deploys the Governance service with its OPA sidecar on OpenShift (plan section 11). The policy set is **pinned**:
`releases/<environment>.json` is the promoted release manifest (written by Workflow C of the policy repository,
`rel-<id>` with one revision per bundle), rendered into the OPA configuration as exact Artifactory paths and mounted
for Governance, which runs in `release` mode (`SPRING_PROFILES_ACTIVE=openshift`). A pod becomes ready only when its
sidecar has downloaded, verified (RS256 signature) and activated all six revisions and `data.governance.core.ready`
is true. Nothing in the running system can change the set; promotion and rollback are Git changes to the release file.

```
helm lint deploy/helm/governance -f deploy/helm/governance/values-dev.yaml --set signing.publicKey="$(cat key.pub)"
helm upgrade --install governance deploy/helm/governance -n <namespace> -f deploy/helm/governance/values-<env>.yaml \
  --set image.tag=<git sha> --wait --atomic --timeout 10m
deploy/helm/scripts/render-check.sh          # lint + template + kubeconform for every environment (CI job "helm")
```

## Objects
Deployment (governance + opa, rolling `maxUnavailable 0`, `maxSurge 1`, `minReadySeconds 10`, anti-affinity,
`checksum/release` and `checksum/config` annotations), Service (8080), Route (edge TLS), ConfigMaps
`-policy-release` (release.json), `-opa-config` (opa.yaml), `-config` (Spring overrides), ServiceAccount (no token
mount), NetworkPolicy (8080 from the router and the admin console, 8080 + 8282 from monitoring, DNS egress plus the
environment's explicit egress list; OPA 8181 unreachable from other pods and bound to loopback), PodDisruptionBudget,
ServiceMonitor (Governance `/actuator/prometheus`, OPA `:8282/metrics`), optional HorizontalPodAutoscaler, optional
ExternalSecret objects. Pod security: `runAsNonRoot`, `RuntimeDefault` seccomp, no `runAsUser` (restricted-v2 assigns
it), containers with `readOnlyRootFilesystem`, no capabilities, `emptyDir` for `/tmp` and `/var/opa`.

## Secret contract (created by External Secrets or by the platform) [A]
| Secret (`secrets.*.name`) | Keys | Used for |
|---|---|---|
| `governance-db` | `url` (JDBC), `username`, `password` | `GOVERNANCE_DB_*` |
| `governance-kafka` | `bootstrap-servers`, `security-protocol`, `sasl-mechanism`, `sasl-jaas-config`, `schema-registry-url`, `schema-registry-auth` | Kafka and Schema Registry clients |
| `artifactory-opa-reader` | `token` | read-only token for `opa-bundles/**`, consumed by the sidecar (`${ARTIFACTORY_TOKEN}`) |

Set `externalSecrets.enabled=true` with `secretStoreRef` and `remoteRefs` to let External Secrets Operator create them.

## Inputs to confirm before the first deploy [A]
Registry (`image.repository`), OPA image digest, Artifactory URL and repository key, the signing public key
(`signing.publicKey` under `signing.keyId`), private CA ConfigMap, IdP issuer, Route hosts, router and monitoring
namespace selectors, egress targets, External Secrets store. `helm template` fails loudly when the release file or the
public key is missing, when a bundle entry has no revision, and when a bundle's `keyId` is neither `signing.keyId` nor
listed under `signing.additionalKeys`. Each bundle is verified with the key id its release entry names (written by the
policy repository's Workflow B), so a rotation adds the new public key under `signing.additionalKeys` first and
retires the old one when no release file references it (`docs/runbook.md`, "Signing key rotation").
`ci/render-check-public-key.pem` is a throwaway public key used only by the render check; it signs nothing.
`ci/release-rotation.json` is the render check's fixture for a release whose bundles were signed under two key ids.

## Operations
- Promotion: Workflow C opens a PR that replaces `releases/<env>.json`; merging triggers `deploy.yml`, which rolls the
  pods; Workflow D verifies every pod reports the release id (`GET /api/v1/admin/policy-releases/current`).
- Rollback: Workflow E (previous verified release file) or `helm rollback` for the application image; the two are
  independent. `helm rollback` also restores the previous release file and OPA configuration immediately; Workflow E
  must still run afterwards so that Git agrees with the cluster (`docs/runbook.md`, "Rollback procedure").
- A pod that never becomes ready: `oc logs <pod> -c opa` (download, signature or activation error) and
  `/actuator/health/readiness` on the governance container (`policyRelease` details list the mismatching bundle).
