#!/usr/bin/env bash
# Local build of the six governance OPA bundles: checks -> tests -> fixtures -> unsigned build per bundle -> release
# manifest. This is the developer and end-to-end-test flow (Governance serves these bundles itself in local mode).
# Signed, optimized, immutable artifacts for Artifactory come from scripts/build-release.sh (Workflow B).
# Usage: scripts/build.sh [revision]      (policies/build.sh forwards here)
#   dist/<bundle>-bundle.tar.gz   one bundle per root, revision "<bundle>-<revision>"
#   dist/release.json             the tested set: {releaseId, revision, bundles: {name: {revision, sha256, file}}}
#   dist/test-report.json         opa test --format=json over all bundles
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OPA=scripts/opa.sh
OPA_VERSION="$(tr -d '[:space:]' < OPA_VERSION)"
REVISION="${1:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
BUNDLES="core manifests naming quotas resources changes"
# tests/fixtures and tests/integration hold JSON and shell, not Rego: keep them out of the test loader.
IGNORE=(--ignore fixtures --ignore integration)
mkdir -p dist

echo ">> roots, lib sync, builtins, contract sync"
scripts/check-roots.sh
scripts/check-lib-sync.sh
scripts/check-builtins.sh
scripts/check-contract-sync.sh
echo ">> opa check --strict"
$OPA check --strict bundles tests "${IGNORE[@]}"
echo ">> opa test"
if ! $OPA test bundles tests "${IGNORE[@]}" --format=json > dist/test-report.json; then
  echo "Policy tests FAILED:"; cat dist/test-report.json; exit 1
fi
PASSED=$(grep -c '"name"' dist/test-report.json || true)
echo "   ${PASSED} tests passed"
echo ">> fixtures"
scripts/eval-fixtures.sh
for b in $BUNDLES; do
  echo ">> opa build ${b} (revision ${b}-${REVISION})"
  $OPA build -b "bundles/${b}" -r "${b}-${REVISION}" --ignore '*_test.rego' -o "dist/${b}-bundle.tar.gz"
done
python3 - "$REVISION" "$OPA_VERSION" $BUNDLES <<'PY'
import hashlib, json, re, sys
rev, opa_version, names = sys.argv[1], sys.argv[2], sys.argv[3:]
bundles = {}
for n in names:
    f = f"dist/{n}-bundle.tar.gz"
    bundles[n] = {"revision": f"{n}-{rev}", "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest(), "file": f"{n}-bundle.tar.gz"}
contract = re.search(r'^contract_version := "([0-9]+\.[0-9]+)"', open("bundles/core/core.rego").read(), re.M).group(1)
digest = hashlib.sha256(",".join(f"{n}={bundles[n]['revision']}" for n in sorted(bundles)).encode()).hexdigest()[:12]
manifest = {"releaseId": "rel-" + digest, "source": "local", "revision": rev, "contractVersion": contract, "opaVersion": opa_version, "bundles": bundles}
json.dump(manifest, open("dist/release.json", "w"), indent=2)
print(">> dist/release.json releaseId=rel-" + digest)
PY
echo "${REVISION}" > dist/revision.txt
echo ">> done: $(ls dist/*-bundle.tar.gz | wc -l | tr -d ' ') bundles, dist/release.json, dist/test-report.json"
