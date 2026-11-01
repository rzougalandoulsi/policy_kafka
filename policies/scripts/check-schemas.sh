#!/usr/bin/env bash
# Contract checks (Workflow A step 7): every fixture input against contracts/input.schema.json, every decision output
# against output.schema.json, the merged catalogue against catalog.schema.json and release manifests against
# release-manifest.schema.json. Needs the python package jsonschema; when it is missing a private virtualenv is
# created under .tools/ with the pinned versions below (the workflows install the same pins).
#   check-schemas.sh [--outputs DIR] [--release FILE]...
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OPA=scripts/opa.sh
PINS="jsonschema==4.26.0 rfc3339-validator==0.1.4"
outputs="" releases=()
while [ $# -gt 0 ]; do
  case "$1" in
    --outputs) outputs="$2"; shift 2 ;;
    --release) releases+=("$2"); shift 2 ;;
    *) echo "usage: $0 [--outputs DIR] [--release FILE]..." >&2; exit 2 ;;
  esac
done
PY="${PYTHON:-python3}"
if ! "$PY" -c 'import jsonschema, rfc3339_validator' 2>/dev/null; then
  if [ ! -x .tools/venv/bin/python ]; then
    echo ">> creating .tools/venv with $PINS"
    python3 -m venv .tools/venv
    .tools/venv/bin/pip install --quiet --disable-pip-version-check $PINS
  fi
  PY=.tools/venv/bin/python
fi
validate() { "$PY" scripts/lib/validate_json.py "$@"; }
status=0
echo ">> fixture inputs against contracts/input.schema.json"
validate contracts/input.schema.json tests/fixtures/inputs/*.json || status=1
if [ -z "$outputs" ]; then
  outputs=dist/fixture-outputs
  scripts/eval-fixtures.sh --out "$outputs" >/dev/null
fi
echo ">> decision outputs against contracts/output.schema.json"
validate contracts/output.schema.json "$outputs"/*.json || status=1
echo ">> expected decisions against contracts/output.schema.json"
validate contracts/output.schema.json tests/fixtures/expected/*.json || status=1
echo ">> merged catalogue against contracts/catalog.schema.json"
mkdir -p dist
$OPA eval -b bundles/core -b bundles/manifests -b bundles/naming -b bundles/quotas -b bundles/resources -b bundles/changes --format=json 'data.governance.core.policy_catalog' \
  | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["result"][0]["expressions"][0]["value"], indent=2))' > dist/catalog.json
validate contracts/catalog.schema.json dist/catalog.json || status=1
if [ ${#releases[@]} -eq 0 ] && [ -f dist/release.json ]; then releases=(dist/release.json); fi
if [ ${#releases[@]} -gt 0 ]; then
  echo ">> release manifests against contracts/release-manifest.schema.json"
  validate contracts/release-manifest.schema.json "${releases[@]}" || status=1
fi
[ $status -eq 0 ] && echo "schemas OK"
exit $status
