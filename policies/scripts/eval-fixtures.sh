#!/usr/bin/env bash
# Evaluates every tests/fixtures/inputs/<name>.json through data.governance.core.decision with all six bundles loaded
# and compares the result (without the runtime-only `revisions` map) to tests/fixtures/expected/<name>.json.
#   eval-fixtures.sh                    bundle sources under bundles/
#   eval-fixtures.sh --artifacts DIR    the six built tarballs <name>-<revision>.tar.gz in DIR (relative to the repo root)
#   eval-fixtures.sh --out DIR          also write each actual decision to DIR/<name>.json (schema checks read them)
#   eval-fixtures.sh --update           rewrite the expected files from the current sources (review the diff!)
# Every fixture must evaluate with ready == true; an ERROR decision is a failure even when expected says so.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OPA=scripts/opa.sh
BUNDLES="core manifests naming quotas resources changes"
artifacts="" out="" update=0
while [ $# -gt 0 ]; do
  case "$1" in
    --artifacts) artifacts="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --update) update=1; shift ;;
    *) echo "usage: $0 [--artifacts DIR] [--out DIR] [--update]" >&2; exit 2 ;;
  esac
done
bundle_args=()
for b in $BUNDLES; do
  if [ -n "$artifacts" ]; then
    files=$(ls "$artifacts"/"$b"-*.tar.gz 2>/dev/null | grep -v '\.sha256$' || true)
    [ "$(wc -l <<< "$files" | tr -d ' ')" = 1 ] && [ -n "$files" ] || { echo "expected exactly one $b-<revision>.tar.gz in $artifacts" >&2; exit 1; }
    bundle_args+=(-b "$files")
  else
    bundle_args+=(-b "bundles/$b")
  fi
done
[ -n "$out" ] && mkdir -p "$out"
status=0
count=0
for f in tests/fixtures/inputs/*.json; do
  name=$(basename "$f" .json)
  count=$((count + 1))
  raw=$($OPA eval "${bundle_args[@]}" -i "$f" --format=json 'data.governance.core.decision')
  actual=$(python3 -c '
import json, sys
r = json.loads(sys.argv[1])
if "errors" in r:
    sys.exit("opa eval failed: " + json.dumps(r["errors"]))
try:
    v = r["result"][0]["expressions"][0]["value"]
except (KeyError, IndexError):
    sys.exit("decision is undefined (are all six bundles loaded?)")
v.pop("revisions", None)
print(json.dumps(v, indent=2, sort_keys=True))
' "$raw") || { echo "FAIL $name: $actual"; status=1; continue; }
  [ -n "$out" ] && printf '%s\n' "$actual" > "$out/$name.json"
  expected="tests/fixtures/expected/$name.json"
  if [ $update -eq 1 ]; then
    printf '%s\n' "$actual" > "$expected"
    echo "updated $expected ($(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["decision"])' "$actual"))"
    continue
  fi
  if [ ! -f "$expected" ]; then echo "FAIL $name: $expected missing (run with --update and review)"; status=1; continue; fi
  if ! verdict=$(python3 -c '
import difflib, json, sys
actual = json.loads(sys.argv[1])
expected = json.load(open(sys.argv[2]))
if actual.get("ready") is not True or actual.get("decision") == "ERROR":
    sys.exit("decision is " + str(actual.get("decision")) + ", ready=" + str(actual.get("ready")))
if actual != expected:
    a = json.dumps(actual, indent=2, sort_keys=True).splitlines()
    e = json.dumps(expected, indent=2, sort_keys=True).splitlines()
    sys.exit("\n".join(difflib.unified_diff(e, a, "expected", "actual", lineterm="")))
print(actual["decision"])
' "$actual" "$expected" 2>&1); then
    echo "FAIL $name:"; echo "$verdict" | sed 's/^/    /'; status=1
  else
    echo "ok   $name -> $verdict"
  fi
done
[ $count -gt 0 ] || { echo "no fixtures under tests/fixtures/inputs" >&2; exit 1; }
[ $status -eq 0 ] && [ $update -eq 0 ] && echo "fixtures OK ($count evaluated)"
exit $status
