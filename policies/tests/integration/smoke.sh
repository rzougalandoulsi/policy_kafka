#!/usr/bin/env bash
# Smoke test of built artifacts the way OPA will run them (Workflow B step 4, reused by Workflow D):
# opa run --server with the six tarballs of a release manifest and signature verification, then
#   - /health?bundles is 200 and data.governance.core.ready is true,
#   - the revisions OPA reports (data.system.bundles) are exactly the manifest's revisions,
#   - every tests/fixtures/inputs/*.json posted to data.governance.core.decision equals tests/fixtures/expected/*.json.
#   smoke.sh --manifest FILE --artifacts DIR --verification-key PUBLIC.pem [--port 18181] [--keyid governance-policies]
# Uses the opa binary when scripts/opa.sh resolves to native, otherwise the pinned Docker image with the port published
# on 127.0.0.1 only.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
OPA=scripts/opa.sh
manifest="" artifacts="" pubkey="" port=18181 keyid="governance-policies"
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest) manifest="$2"; shift 2 ;;
    --artifacts) artifacts="$2"; shift 2 ;;
    --verification-key) pubkey="$2"; shift 2 ;;
    --port) port="$2"; shift 2 ;;
    --keyid) keyid="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$manifest" ] && [ -n "$artifacts" ] && [ -n "$pubkey" ] || { echo "usage: $0 --manifest FILE --artifacts DIR --verification-key PUB [--port N]" >&2; exit 2; }
revisions=()
bundle_args=()
while IFS= read -r r; do
  [ -f "$artifacts/$r.tar.gz" ] || { echo "$artifacts/$r.tar.gz missing (run scripts/assemble-release.sh)" >&2; exit 1; }
  revisions+=("$r")
  bundle_args+=(-b "$artifacts/$r.tar.gz")
done < <(python3 -c 'import json,sys; [print(b["revision"]) for b in json.load(open(sys.argv[1]))["bundles"].values()]' "$manifest")
mode="$($OPA --mode)"
container="opa-smoke-$$"
cleanup() {
  if [ "$mode" = docker ]; then docker stop "$container" >/dev/null 2>&1 || true
  elif [ -n "${server_pid:-}" ]; then kill "$server_pid" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
if [ "$mode" = docker ]; then
  abs_key="$(cd "$(dirname "$pubkey")" && pwd)/$(basename "$pubkey")"
  docker run --rm -d --name "$container" -p "127.0.0.1:${port}:8181" -v "$PWD:/w" -w /w -v "$abs_key:/keys/public.pem:ro" \
    "openpolicyagent/opa:$(tr -d '[:space:]' < OPA_VERSION)" run --server --addr 0.0.0.0:8181 --log-level error \
    --verification-key /keys/public.pem --verification-key-id "$keyid" "${bundle_args[@]}" >/dev/null
else
  $OPA run --server --addr "127.0.0.1:${port}" --log-level error --verification-key "$pubkey" --verification-key-id "$keyid" "${bundle_args[@]}" &
  server_pid=$!
fi
url="http://127.0.0.1:${port}"
for _ in $(seq 1 40); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$url/health?bundles" || true)" = 200 ]; then break; fi
  if [ "$mode" = docker ] && ! docker ps -q --filter "name=$container" | grep -q .; then echo "OPA exited:"; docker logs "$container" 2>&1 | tail -5; exit 1; fi
  if [ "$mode" = native ] && ! kill -0 "$server_pid" 2>/dev/null; then echo "OPA exited (signature or load error above)"; exit 1; fi
  sleep 0.5
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$url/health?bundles")" = 200 ] || { echo "OPA never became healthy with bundles"; exit 1; }
echo "ok   /health?bundles"
ready="$(curl -s "$url/v1/data/governance/core/ready" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("result"))')"
[ "$ready" = True ] || { echo "data.governance.core.ready is $ready"; exit 1; }
echo "ok   ready == true"
curl -s "$url/v1/data/system/bundles" | python3 -c '
import json, sys
loaded = sorted(b["manifest"]["revision"] for b in json.load(sys.stdin)["result"].values())
expected = sorted(sys.argv[1:])
if loaded != expected:
    sys.exit("loaded revisions %s differ from manifest %s" % (loaded, expected))
print("ok   revisions", ", ".join(loaded))
' "${revisions[@]}"
status=0
for f in tests/fixtures/inputs/*.json; do
  name="$(basename "$f" .json)"
  python3 - "$url" "$f" "tests/fixtures/expected/$name.json" <<'PY' || status=1
import json, sys, urllib.request
url, inp, exp = sys.argv[1:4]
body = json.dumps({"input": json.load(open(inp))}).encode()
req = urllib.request.Request(url + "/v1/data/governance/core/decision", data=body, headers={"Content-Type": "application/json"})
actual = json.load(urllib.request.urlopen(req, timeout=10))["result"]
actual.pop("revisions", None)
expected = json.load(open(exp))
if actual != expected:
    print("FAIL", inp, json.dumps(actual, sort_keys=True)[:600])
    sys.exit(1)
print("ok  ", inp.split("/")[-1], "->", actual["decision"])
PY
done
[ $status -eq 0 ] && echo "smoke OK"
exit $status
