#!/usr/bin/env bash
# Builds the governance OPA bundle: check -> test -> build. Requires Docker (no local opa binary needed).
# Usage: policies/build.sh [revision]
set -euo pipefail
cd "$(dirname "$0")"
OPA_IMAGE="${OPA_IMAGE:-openpolicyagent/opa:1.22.0}"
REVISION="${1:-$(git -C .. rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
mkdir -p dist
run() { docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" "$@"; }

echo ">> opa check"
run check --strict governance
echo ">> opa test"
if ! run test governance --format=json > dist/test-report.json; then
  echo "Policy tests FAILED:"; cat dist/test-report.json; exit 1
fi
PASSED=$(grep -c '"name"' dist/test-report.json || true)
echo "   ${PASSED} tests passed"
echo ">> opa build (revision ${REVISION})"
run build -b governance -r "$REVISION" --ignore '*_test.rego' -o dist/governance-bundle.tar.gz
echo "${REVISION}" > dist/revision.txt
echo ">> dist/governance-bundle.tar.gz (revision ${REVISION}), dist/test-report.json"
