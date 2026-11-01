#!/usr/bin/env bash
# Everything Workflow A (validate.yml) runs, in the same order, so a change can be proven locally before the pull
# request: fmt, check --strict, tests with coverage threshold, roots and imports, lib copies, forbidden builtins,
# contract sync, fixtures and schemas, Regal lint, secret scan, per-bundle build dry run.
#   SKIP_LINT=1 / SKIP_SECRETS=1 skip the two steps that need extra tools (never skipped in CI).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OPA=scripts/opa.sh
COVERAGE_THRESHOLD="${COVERAGE_THRESHOLD:-85}"
GITLEAKS_VERSION="${GITLEAKS_VERSION:-v8.30.1}"
IGNORE=(--ignore fixtures --ignore integration)
step() { printf '\n== %s\n' "$*"; }
mkdir -p dist
step "opa fmt --diff"
if ! $OPA fmt --diff --fail bundles tests; then echo "run: scripts/opa.sh fmt --write bundles tests"; exit 1; fi
step "opa check --strict"
$OPA check --strict bundles tests "${IGNORE[@]}"
step "opa test"
$OPA test bundles tests "${IGNORE[@]}"
step "coverage threshold ${COVERAGE_THRESHOLD}%"
$OPA test bundles tests "${IGNORE[@]}" --coverage --threshold "$COVERAGE_THRESHOLD" --format=json > dist/coverage.json \
  || { python3 -c 'import json; print("coverage %.1f%%" % json.load(open("dist/coverage.json"))["coverage"])'; echo "coverage below ${COVERAGE_THRESHOLD}%"; exit 1; }
python3 -c 'import json; print("coverage %.1f%%" % json.load(open("dist/coverage.json"))["coverage"])'
step "roots and cross-bundle imports"
scripts/check-roots.sh
step "lib copies"
scripts/check-lib-sync.sh
step "forbidden builtins"
scripts/check-builtins.sh
step "contract sync"
scripts/check-contract-sync.sh
step "fixtures: expected decisions"
scripts/eval-fixtures.sh --out dist/fixture-outputs
step "schemas: inputs, outputs, catalogue"
scripts/check-schemas.sh --outputs dist/fixture-outputs
if [ "${SKIP_LINT:-0}" != 1 ]; then step "regal lint"; scripts/lint.sh bundles tests; fi
if [ "${SKIP_SECRETS:-0}" != 1 ]; then
  step "gitleaks (${GITLEAKS_VERSION})"
  docker run --rm -v "$PWD:/repo:ro" "ghcr.io/gitleaks/gitleaks:${GITLEAKS_VERSION}" dir /repo --no-banner --redact --config /repo/.gitleaks.toml
fi
step "opa build dry run per bundle"
mkdir -p dist/dry-run
for b in core manifests naming quotas resources changes; do
  $OPA build -b "bundles/$b" --ignore '*_test.rego' -o "dist/dry-run/$b.tar.gz"
  echo "   $b builds"
done
printf '\nvalidate OK\n'
