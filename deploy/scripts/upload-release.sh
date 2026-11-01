#!/usr/bin/env bash
# Uploads the six built bundles of policies/dist/release.json (+ the shared test report) as new bundle versions,
# activates them together as one policy release, and waits until OPA runs exactly that release.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -f policies/dist/release.json ] || policies/build.sh
BASE="${GOVERNANCE_URL:-http://localhost:8080}"
TOKEN="$(deploy/scripts/token.sh admin-1)"
REPORT="$(python3 -c 'import json,sys; print(json.dumps(open(sys.argv[1]).read()))' policies/dist/test-report.json)"
IDS=()
for name in $(python3 -c 'import json; print(" ".join(json.load(open("policies/dist/release.json"))["bundles"]))'); do
  B64="$(base64 < "policies/dist/${name}-bundle.tar.gz" | tr -d '\n')"
  BODY="$(printf '{"bundleBase64":"%s","testReportJson":%s,"comment":"%s"}' "$B64" "$REPORT" "release $(cat policies/dist/revision.txt) ${name}")"
  ID="$(curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY" "$BASE/api/v1/admin/policy-bundles" | sed -E 's/.*"id":"([^"]+)".*/\1/')"
  echo "uploaded ${name}: $ID"
  IDS+=("\"$ID\"")
done
LIST="$(IFS=,; echo "${IDS[*]}")"
RELEASE="$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"versionIds\":[${LIST}]}" "$BASE/api/v1/admin/policy-releases")"
echo "activated release $(echo "$RELEASE" | sed -E 's/.*"id":"([^"]+)".*/\1/')"
for i in $(seq 1 30); do
  if curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/admin/policy-releases/current" | grep -q '"match":true'; then
    echo "OPA adopted the release"; exit 0; fi
  sleep 2
done
echo "OPA did not adopt the release yet; check: curl -H 'Authorization: Bearer <admin token>' $BASE/api/v1/admin/policy-releases/current" >&2
exit 1
