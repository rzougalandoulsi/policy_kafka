#!/usr/bin/env bash
# Workflow D, last step: records a successful verification as an immutable file so that Workflow E only accepts
# previously verified releases as rollback targets.
#   mark-verified.sh --release-id rel-xxx --environment ENV [--run URL]
# Writes releases/<env>/verified/<releaseId>.json once (first verification) and an event file every time.
set -euo pipefail
release_id="" env="" run="${GITHUB_SERVER_URL:-}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-local}"
while [ $# -gt 0 ]; do
  case "$1" in
    --release-id) release_id="$2"; shift 2 ;;
    --environment) env="$2"; shift 2 ;;
    --run) run="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$release_id" ] && [ -n "$env" ] || { echo "usage: $0 --release-id ID --environment ENV" >&2; exit 2; }
: "${ARTIFACTORY_URL:?}" "${ARTIFACTORY_REPOSITORY:?}" "${ARTIFACTORY_TOKEN:?}"
base="${ARTIFACTORY_URL%/}/${ARTIFACTORY_REPOSITORY}"
auth=(-H "Authorization: Bearer ${ARTIFACTORY_TOKEN}")
ts="$(date -u +%Y%m%dT%H%M%SZ)"
event_id="$ts-$(python3 -c 'import secrets; print(secrets.token_hex(3))')"
tmp="$(mktemp -d)"
printf '{"event":"verified","environment":"%s","releaseId":"%s","at":"%s","run":"%s"}\n' "$env" "$release_id" "$ts" "$run" > "$tmp/event.json"
curl -sS --fail-with-body -o /dev/null -X PUT "${auth[@]}" -T "$tmp/event.json" "$base/releases/$env/events/$event_id-verified-$release_id.json" || { echo "event PUT failed"; exit 1; }
echo "PUT releases/$env/events/$event_id-verified-$release_id.json"
code="$(curl -sS -o /dev/null -w '%{http_code}' -I "${auth[@]}" "$base/releases/$env/verified/$release_id.json")"
if [ "$code" = 200 ]; then echo "releases/$env/verified/$release_id.json already recorded"; else
  curl -sS --fail-with-body -o /dev/null -X PUT "${auth[@]}" -T "$tmp/event.json" "$base/releases/$env/verified/$release_id.json" || { echo "marker PUT failed"; exit 1; }
  echo "PUT releases/$env/verified/$release_id.json"
fi
echo "verified: $release_id in $env"
