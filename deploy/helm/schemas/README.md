# Local CRD schemas for kubeconform

`deploy/helm/scripts/render-check.sh` validates the rendered charts against the Kubernetes schemas and the datree
CRD catalog (ServiceMonitor, ExternalSecret). The catalog has no OpenShift `Route`, so
`route.openshift.io/route_v1.json` is vendored here, converted from the upstream CRD (`openshift/api`,
`route/v1/zz_generated.crd-manifests/routes.crd.yaml`): the structural schema (types, enums, required fields,
patterns, length and range limits, int-or-string) is kept; the CRD's cross-field `allOf`/`anyOf`/`not` rules
(for example "passthrough TLS cannot carry a path") are left out because kubeconform's schema loader rejects them.
Those rules are still enforced by the cluster at `oc apply`. Refresh the file when the Route API changes
(`mikefarah/yq -o=json '.spec.versions[] | select(.name == "v1") | .schema.openAPIV3Schema'`, then the same
translation as in the Phase 4 notes of the plan).
