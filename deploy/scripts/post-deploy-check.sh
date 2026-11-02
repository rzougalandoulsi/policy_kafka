#!/usr/bin/env bash
# After `helm upgrade`: liveness, readiness, the policy release every pod reports, and a smoke request.
#   post-deploy-check.sh --base-url https://governance-dev.apps.example.com --environment dev [--token <jwt>]
#                        [--namespace ns --deployment governance]   (per-pod fan-out through `oc exec`)
# dev / staging: POST a known-good manifest for the sandbox team and wait for COMPLETED (needs a token with the
# sandbox team and governance-auditor; obtained by the deploy workflow from the environment's IdP test client [A]).
# production: read-only checks (current release, policy catalogue).
set -euo pipefail
base="" env="" token="" namespace="" deployment="governance" manifest="docs/examples/manifest.json"
while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) base="${2%/}"; shift 2 ;;
    --environment) env="$2"; shift 2 ;;
    --token) token="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --deployment) deployment="$2"; shift 2 ;;
    --manifest) manifest="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$base" ] && [ -n "$env" ] || { echo "usage: $0 --base-url URL --environment dev|staging|production [--token JWT] [--namespace NS]" >&2; exit 2; }
auth=()
[ -n "$token" ] && auth=(-H "Authorization: Bearer $token")
status=0
code() { curl -sS -o "$2" -w '%{http_code}' "${@:3}" "$1"; }
tmp="$(mktemp -d)"
echo "== liveness and readiness"
[ "$(code "$base/actuator/health/liveness" "$tmp/l")" = 200 ] && echo "ok   liveness" || { echo "FAIL liveness"; status=1; }
[ "$(code "$base/actuator/health/readiness" "$tmp/r")" = 200 ] && echo "ok   readiness: $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["components"]["policyRelease"]["details"].get("releaseId"))' "$tmp/r")" \
  || { echo "FAIL readiness: $(cat "$tmp/r")"; status=1; }
if [ -n "$token" ]; then
  echo "== policy release (through the Route)"
  if [ "$(code "$base/api/v1/admin/policy-releases/current" "$tmp/c" "${auth[@]}")" = 200 ]; then
    python3 - "$tmp/c" <<'PY' || status=1
import json, sys
d = json.load(open(sys.argv[1]))
print("     release", d["releaseId"], "mode", d.get("mode"), "match", d["match"], "ready", d["ready"])
bad = [n for n, b in d["bundles"].items() if not b["match"]]
if d.get("mode") != "release" or not d["match"] or not d["ready"] or bad:
    sys.exit("FAIL release mismatch: " + ", ".join(bad))
print("ok   every bundle matches the mounted release")
PY
  else echo "FAIL policy-releases/current: $(cat "$tmp/c")"; status=1; fi
  echo "== policy catalogue"
  [ "$(code "$base/api/v1/admin/policies" "$tmp/p" "${auth[@]}")" = 200 ] && echo "ok   catalogue ($(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$tmp/p") policies)" || { echo "FAIL catalogue"; status=1; }
fi
if [ -n "$namespace" ] && command -v oc >/dev/null 2>&1; then
  echo "== per pod (oc exec): every replica reports the same release"
  expected=""
  for pod in $(oc -n "$namespace" get pods -l "app.kubernetes.io/name=$deployment" -o jsonpath='{.items[*].metadata.name}'); do
    rel="$(oc -n "$namespace" exec "$pod" -c governance -- curl -s localhost:8080/actuator/health/readiness | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d["components"]["policyRelease"]["details"].get("releaseId"))')"
    echo "     $pod: $rel"
    case "$rel" in UP\ *) ;; *) echo "FAIL $pod not ready"; status=1 ;; esac
    [ -z "$expected" ] && expected="$rel"
    [ "$rel" = "$expected" ] || { echo "FAIL $pod reports a different release"; status=1; }
  done
fi
if [ -n "$token" ] && [ "$env" != production ]; then
  echo "== smoke: submit $manifest and wait for COMPLETED"
  key="smoke-$(date +%s)-$RANDOM"
  c="$(code "$base/api/v1/manifests" "$tmp/s" "${auth[@]}" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' --data-binary "@$manifest")"
  if [ "$c" = 202 ]; then
    rid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["requestId"])' "$tmp/s")"
    for _ in $(seq 1 60); do
      st="$(curl -sS "${auth[@]}" "$base/api/v1/requests/$rid" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status"))' 2>/dev/null || echo unknown)"
      case "$st" in COMPLETED) echo "ok   request $rid COMPLETED"; break ;; FAILED|REJECTED|DENIED|MANIFEST_REJECTED) echo "FAIL request $rid $st"; status=1; break ;; esac
      sleep 5
    done
    [ "$st" = COMPLETED ] || { [ $status -eq 0 ] && { echo "FAIL request $rid still $st"; status=1; }; }
  else echo "FAIL submission returned $c: $(cat "$tmp/s")"; status=1; fi
fi
[ $status -eq 0 ] && echo "post-deploy-check OK ($env)"
exit $status
