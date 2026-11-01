#!/usr/bin/env bash
# Builds the six governance OPA bundles: check -> test -> build each -> release manifest. Requires Docker (no local opa binary needed).
# Usage: policies/build.sh [revision]
#   dist/<bundle>-bundle.tar.gz   one signed-off bundle per root (revision "<bundle>-<revision>")
#   dist/release.json             the tested set: {releaseId, revision, bundles: {name: {revision, sha256, file}}}
#   dist/test-report.json         opa test --format=json over all bundles
set -euo pipefail
cd "$(dirname "$0")"
OPA_IMAGE="${OPA_IMAGE:-openpolicyagent/opa:1.22.0}"
REVISION="${1:-$(git -C .. rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
BUNDLES="core manifests naming quotas resources changes"
mkdir -p dist
run() { docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" "$@"; }

echo ">> roots and lib sync"
scripts/check-roots.sh
scripts/check-lib-sync.sh
echo ">> opa check"
run check --strict bundles tests
echo ">> opa test"
if ! run test bundles tests --format=json > dist/test-report.json; then
  echo "Policy tests FAILED:"; cat dist/test-report.json; exit 1
fi
PASSED=$(grep -c '"name"' dist/test-report.json || true)
echo "   ${PASSED} tests passed"
for b in $BUNDLES; do
  echo ">> opa build ${b} (revision ${b}-${REVISION})"
  run build -b "bundles/${b}" -r "${b}-${REVISION}" --ignore '*_test.rego' -o "dist/${b}-bundle.tar.gz"
done
python3 - "$REVISION" $BUNDLES <<'PY'
import hashlib, json, sys
rev, names = sys.argv[1], sys.argv[2:]
bundles = {}
for n in names:
    f = f"dist/{n}-bundle.tar.gz"
    bundles[n] = {"revision": f"{n}-{rev}", "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest(), "file": f"{n}-bundle.tar.gz"}
digest = hashlib.sha256(",".join(f"{n}={bundles[n]['revision']}" for n in sorted(bundles)).encode()).hexdigest()[:12]
json.dump({"releaseId": "rel-" + digest, "revision": rev, "bundles": bundles}, open("dist/release.json", "w"), indent=2)
print(">> dist/release.json releaseId=rel-" + digest)
PY
echo "${REVISION}" > dist/revision.txt
echo ">> done: $(ls dist/*-bundle.tar.gz | wc -l | tr -d ' ') bundles, dist/release.json, dist/test-report.json"
