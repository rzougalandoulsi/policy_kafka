#!/usr/bin/env bash
# Lints and renders both charts for every environment file and validates the manifests with kubeconform
# (Kubernetes schemas plus the OpenShift / Prometheus / External Secrets CRD schemas from the datree catalog).
# Same script locally and in the "helm" CI job. Uses the helm/kubeconform binaries when present, Docker otherwise.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.19.0}"
KUBECONFORM_IMAGE="${KUBECONFORM_IMAGE:-ghcr.io/yannh/kubeconform:v0.7.0}"
K8S_VERSION="${K8S_VERSION:-1.31.0}"
OUT="${OUT:-target/helm-render}"
mkdir -p "$OUT"
helm() { if type -P helm >/dev/null 2>&1; then "$(type -P helm)" "$@"; else docker run --rm -v "$PWD:/w" -w /w "$HELM_IMAGE" "$@"; fi; }
kubeconform() {
  if type -P kubeconform >/dev/null 2>&1; then "$(type -P kubeconform)" "$@"; else docker run --rm -v "$PWD:/w" -w /w "$KUBECONFORM_IMAGE" "$@"; fi
}
PUBKEY="$(cat deploy/helm/governance/ci/render-check-public-key.pem)"
status=0
for env in dev staging production; do
  for chart in governance admin-ui; do
    dir="deploy/helm/$chart"
    extra=()
    [ "$chart" = governance ] && extra=(--set "signing.publicKey=$PUBKEY" --set externalSecrets.enabled=true --set artifactory.caBundleConfigMap=enterprise-ca)
    echo "== helm lint $chart ($env)"
    helm lint "$dir" -f "$dir/values-$env.yaml" --set image.tag=render-check ${extra[@]+"${extra[@]}"} --strict || status=1
    echo "== helm template $chart ($env)"
    helm template "$chart" "$dir" -n "governance-$env" -f "$dir/values-$env.yaml" --set image.tag=render-check ${extra[@]+"${extra[@]}"} > "$OUT/$chart-$env.yaml" || { status=1; continue; }
    kubeconform -strict -summary -kubernetes-version "$K8S_VERSION" \
      -schema-location default \
      -schema-location 'deploy/helm/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
      -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
      "$OUT/$chart-$env.yaml" || status=1
  done
done
echo "== invariants (governance, dev)"
rendered="$OUT/governance-dev.yaml"
grep -q -- '--addr=127.0.0.1:8181' "$rendered" && echo "ok   OPA Data API bound to loopback" || { echo "FAIL OPA addr"; status=1; }
grep -q 'readOnlyRootFilesystem: true' "$rendered" && echo "ok   read-only root filesystems" || { echo "FAIL readOnlyRootFilesystem"; status=1; }
! grep -qE '^[[:space:]]*runAsUser:' "$rendered" && echo "ok   no runAsUser (restricted-v2 assigns the UID)" || { echo "FAIL runAsUser present"; status=1; }
grep -q 'path: "/health?bundles&plugins"' "$rendered" && echo "ok   sidecar readiness waits for every bundle" || { echo "FAIL opa readiness"; status=1; }
grep -q 'path: /actuator/health/readiness' "$rendered" && echo "ok   governance readiness probe" || { echo "FAIL governance readiness"; status=1; }
grep -q 'resource: "core/core-' "$rendered" && echo "ok   pinned bundle paths rendered from releases/dev.json" || { echo "FAIL pinned paths"; status=1; }
grep -q 'keyid: "governance-policies"' "$rendered" && echo "ok   signature verification configured" || { echo "FAIL signing"; status=1; }
! grep -qE 'port: 8181' "$rendered" && echo "ok   no NetworkPolicy or Service exposes 8181" || { echo "FAIL 8181 exposed"; status=1; }
echo "== key rotation (ci/release-rotation.json: one bundle signed under governance-policies-2)"
rot=(--set image.tag=render-check --set "signing.publicKey=$PUBKEY" --set release.file=ci/release-rotation.json)
if helm template governance deploy/helm/governance -n governance-dev -f deploy/helm/governance/values-dev.yaml "${rot[@]}" \
     --set "signing.additionalKeys.governance-policies-2=$PUBKEY" > "$OUT/governance-rotation.yaml"; then
  if grep -q 'keyid: "governance-policies-2"' "$OUT/governance-rotation.yaml" && grep -q 'keyid: "governance-policies"' "$OUT/governance-rotation.yaml" \
     && grep -qE '^      governance-policies-2:' "$OUT/governance-rotation.yaml"; then echo "ok   per-bundle key ids rendered, both keys trusted"; else echo "FAIL rotation render"; status=1; fi
else echo "FAIL rotation render"; status=1; fi
if helm template governance deploy/helm/governance -n governance-dev -f deploy/helm/governance/values-dev.yaml "${rot[@]}" > /dev/null 2> "$OUT/rotation-unknown-key.log"; then
  echo "FAIL rendered although a bundle names a key id the chart does not trust"; status=1
else grep -q "neither signing.keyId nor listed under signing.additionalKeys" "$OUT/rotation-unknown-key.log" && echo "ok   unknown key id refused at template time" || { echo "FAIL unexpected rotation error"; cat "$OUT/rotation-unknown-key.log"; status=1; }; fi
[ $status -eq 0 ] && echo "render-check OK ($OUT)"
exit $status
