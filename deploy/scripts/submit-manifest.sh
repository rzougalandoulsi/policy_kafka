#!/usr/bin/env bash
# Submits a manifest as dev-1 and follows the request until it reaches a terminal state.
set -euo pipefail
FILE="${1:-docs/examples/manifest.json}"
cd "$(dirname "$0")/../.."
TOKEN="$(deploy/scripts/token.sh dev-1)"
RESP="$(curl -sS -w '\n%{http_code}' -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d @"$FILE" localhost:8080/api/v1/manifests)"
CODE="$(tail -n1 <<<"$RESP")"; BODY="$(sed '$d' <<<"$RESP")"
echo "HTTP $CODE: $BODY"
[ "$CODE" = "202" ] || exit 0
ID="$(sed -E 's/.*"requestId":"([^"]+)".*/\1/' <<<"$BODY")"
for i in $(seq 1 60); do
  STATUS="$(curl -sS -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/requests/$ID" | sed -E 's/.*"status":"([^"]+)".*/\1/')"
  echo "  $ID -> $STATUS"
  case "$STATUS" in COMPLETED|FAILED|*REJECTED*|*DENIED*|STALE_PLAN|APPROVAL_EXPIRED) break;; esac
  sleep 2
done
echo "decisions:"; curl -sS -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/requests/$ID/decisions"; echo
