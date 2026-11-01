#!/usr/bin/env bash
# Uploads policies/dist/governance-bundle.tar.gz (+ test report) as a new bundle version and activates it.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -f policies/dist/governance-bundle.tar.gz ] || policies/build.sh
TOKEN="$(deploy/scripts/token.sh admin-1)"
BUNDLE_B64="$(base64 < policies/dist/governance-bundle.tar.gz | tr -d '\n')"
REPORT="$(python3 -c 'import json,sys; print(json.dumps(open(sys.argv[1]).read()))' policies/dist/test-report.json)"
BODY="$(printf '{"bundleBase64":"%s","testReportJson":%s,"comment":"%s"}' "$BUNDLE_B64" "$REPORT" "revision $(cat policies/dist/revision.txt)")"
ID="$(curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY" localhost:8080/api/v1/admin/policy-bundles | sed -E 's/.*"id":"([^"]+)".*/\1/')"
echo "uploaded bundle $ID"
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/admin/policy-bundles/$ID/activate" >/dev/null && echo "activated"
for i in $(seq 1 30); do
  if curl -fsS -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/admin/policy-bundles/diagnostics | grep -q '"match":true'; then
    echo "OPA adopted the bundle"; exit 0; fi
  sleep 2
done
echo "OPA did not adopt the bundle yet; check: curl -H 'Authorization: Bearer <admin token>' localhost:8080/api/v1/admin/policy-bundles/diagnostics" >&2
exit 1
