#!/usr/bin/env bash
# Workflow D against a deployed namespace: every governance pod reports the expected release through its readiness
# probe (component policyRelease: releaseId, match, ready, per-bundle revisions), its OPA sidecar reports all
# bundles active, and each fixture evaluated against each sidecar's loopback Data API (through `oc exec`) equals
# tests/fixtures/expected. The sidecar's Data API is reachable from nowhere else, so this is the only smoke path.
#   verify-active.sh --namespace NS [--deployment governance] [--release-id rel-xxx] [--skip-fixtures]
# Prints the release id the pods agree on (use it when none was given).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ns="" deployment="governance" expected="" fixtures=1
while [ $# -gt 0 ]; do
  case "$1" in
    --namespace) ns="$2"; shift 2 ;;
    --deployment) deployment="$2"; shift 2 ;;
    --release-id) expected="$2"; shift 2 ;;
    --skip-fixtures) fixtures=0; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$ns" ] || { echo "usage: $0 --namespace NS [--deployment NAME] [--release-id ID]" >&2; exit 2; }
command -v oc >/dev/null || { echo "oc is required" >&2; exit 1; }
pods="$(oc -n "$ns" get pods -l "app.kubernetes.io/name=$deployment" -o jsonpath='{.items[*].metadata.name}')"
[ -n "$pods" ] || { echo "no pods for app.kubernetes.io/name=$deployment in $ns"; exit 1; }
status=0
agreed=""
for pod in $pods; do
  echo "== $pod"
  readiness="$(oc -n "$ns" exec "$pod" -c governance -- curl -s localhost:8080/actuator/health/readiness || echo '{}')"
  rel="$(python3 - "$readiness" "$expected" <<'PY'
import json, sys
d = json.loads(sys.argv[1]); expected = sys.argv[2]
c = d.get("components", {}).get("policyRelease", {})
det = c.get("details", {})
ok = d.get("status") == "UP" and c.get("status") == "UP" and det.get("match") and det.get("ready")
print("   readiness", d.get("status"), "policyRelease", c.get("status"), "release", det.get("releaseId"), "match", det.get("match"), "ready", det.get("ready"), file=sys.stderr)
for n, b in sorted(det.get("bundles", {}).items()):
    print("     ", n, "expected", b.get("expected"), "loaded", b.get("loaded"), "match" if b.get("match") else "MISMATCH", file=sys.stderr)
if expected and det.get("releaseId") != expected:
    print("   FAIL pod reports", det.get("releaseId"), "expected", expected, file=sys.stderr); ok = False
print(det.get("releaseId") or "")
sys.exit(0 if ok else 1)
PY
)" || status=1
  if [ -n "$rel" ]; then
    [ -z "$agreed" ] && agreed="$rel"
    [ "$agreed" = "$rel" ] || { echo "   FAIL pods disagree: $agreed vs $rel"; status=1; }
  fi
  sidecar="$(oc -n "$ns" exec "$pod" -c governance -- curl -s -o /dev/null -w '%{http_code}' 'localhost:8282/health?bundles&plugins' || echo 000)"
  [ "$sidecar" = 200 ] && echo "   ok   sidecar /health?bundles&plugins" || { echo "   FAIL sidecar health $sidecar"; status=1; }
  if [ $fixtures -eq 1 ]; then
    for f in tests/fixtures/inputs/*.json; do
      name="$(basename "$f" .json)"
      actual="$(python3 -c 'import json,sys; print(json.dumps({"input": json.load(open(sys.argv[1]))}))' "$f" \
        | oc -n "$ns" exec -i "$pod" -c governance -- curl -s -X POST -H 'Content-Type: application/json' --data-binary @- localhost:8181/v1/data/governance/core/decision || echo '{}')"
      python3 - "$actual" "tests/fixtures/expected/$name.json" "$name" <<'PY' || status=1
import json, sys
actual = json.loads(sys.argv[1]).get("result"); expected = json.load(open(sys.argv[2])); name = sys.argv[3]
if actual is None: sys.exit("   FAIL %s: no decision" % name)
actual.pop("revisions", None)
if actual != expected: sys.exit("   FAIL %s: decision %s differs from expected" % (name, actual.get("decision")))
print("   ok   fixture", name, "->", actual["decision"])
PY
    done
  fi
done
[ -n "$agreed" ] || { echo "no pod reported a release id"; exit 1; }
[ -n "${GITHUB_OUTPUT:-}" ] && echo "release-id=$agreed" >> "$GITHUB_OUTPUT"
if [ $status -eq 0 ]; then echo "verify-active OK: every pod of $deployment in $ns runs $agreed"; else echo "verify-active FAILED ($agreed)"; fi
echo "$agreed"
exit $status
