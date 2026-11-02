#!/usr/bin/env bash
# Proves that Workflow A's gates fail on broken input (plan Phase 3 acceptance: "a PR with a root violation or a
# failing test cannot merge"). Each case copies the repository to a scratch directory, breaks one thing and runs the
# same script the workflow runs; the case passes when that script exits non-zero.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
work="$(mktemp -d "${TMPDIR:-/tmp}/validate-negative.XXXXXX")"
failures=0
fresh_copy() {
  local dir="$work/$1"
  mkdir -p "$dir"
  tar -cf - --exclude ./dist --exclude ./.tools . | tar -xf - -C "$dir"
  echo "$dir"
}
expect_failure() {
  local label="$1"; shift
  if "$@" > "$work/last.log" 2>&1; then
    echo "FAIL $label: command succeeded but must fail"; tail -5 "$work/last.log"; failures=$((failures + 1))
  else
    echo "PASS $label: $(grep -m1 -E 'outside root|prefix of|FAIL|not allowed|forbidden|differs|required property|error|out of date|^\+' "$work/last.log" | cut -c1-110)"
  fi
}

d="$(fresh_copy root-violation)"
printf 'package governance.quotas.sneaky\n\nimport rego.v1\n\nx := 1\n' > "$d/bundles/naming/sneaky.rego"
expect_failure "root violation: check-roots" "$d/scripts/check-roots.sh"
expect_failure "root violation: opa build dry run" "$d/scripts/opa.sh" build -b bundles/naming -o dist/x.tar.gz

d="$(fresh_copy cross-import)"
printf 'package governance.naming.extra\n\nimport data.governance.quotas.lib\n\nx := 1\n' > "$d/bundles/naming/extra.rego"
expect_failure "cross-bundle import: check-roots" "$d/scripts/check-roots.sh"

d="$(fresh_copy failing-test)"
printf 'package governance.naming_test\n\ntest_always_fails if {\n\t1 == 2\n}\n' > "$d/tests/broken_test.rego"
expect_failure "failing Rego test: opa test" "$d/scripts/opa.sh" test bundles tests --ignore fixtures --ignore integration

d="$(fresh_copy wrong-expected)"
python3 - "$d/tests/fixtures/expected/request-allow.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["decision"] = "DENY"; json.dump(m, open(p, "w"), indent=2)
PY
expect_failure "fixture whose expected decision no longer matches: eval-fixtures" "$d/scripts/eval-fixtures.sh"

d="$(fresh_copy invalid-fixture)"
python3 - "$d/tests/fixtures/inputs/request-allow.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m.pop("now"); m["team"]["source"] = "guess"; json.dump(m, open(p, "w"), indent=2)
PY
expect_failure "fixture violating input.schema.json: check-schemas" "$d/scripts/check-schemas.sh"

d="$(fresh_copy forbidden-builtin)"
printf 'package governance.naming.remote\n\nlookup := http.send({"method": "get", "url": "http://example.invalid"})\n' > "$d/bundles/naming/remote.rego"
expect_failure "http.send in a policy: check-builtins" "$d/scripts/check-builtins.sh"

d="$(fresh_copy lib-drift)"
printf '\n# drift\n' >> "$d/bundles/quotas/lib.rego"
expect_failure "lib copy drift: check-lib-sync" "$d/scripts/check-lib-sync.sh"

d="$(fresh_copy stale-inventory)"
cp ../docs/policy-inventory.html "$work/stale-inventory-docs.html"
sed -i.bak 's/"description": "Topic names must match configuration naming.topicPattern."/"description": "changed"/' "$d/bundles/naming/naming.rego" && rm -f "$d/bundles/naming/naming.rego.bak"
expect_failure "catalogue changed without regenerating the inventory page: inventory --check" "$d/scripts/inventory.sh" --check --out "$work/stale-inventory-docs.html"

d="$(fresh_copy unformatted)"
printf 'package governance.naming.ugly\n\nx   :=    1\n' > "$d/bundles/naming/ugly.rego"
expect_failure "unformatted Rego: opa fmt --fail" "$d/scripts/opa.sh" fmt --diff --fail bundles

if [ $failures -eq 0 ]; then echo; echo "validate-negative OK: every broken tree was rejected"; else echo; echo "$failures case(s) were NOT rejected"; exit 1; fi
