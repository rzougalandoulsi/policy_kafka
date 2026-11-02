#!/usr/bin/env bash
# Workflow C: promotes a release to an environment. No artifact is rebuilt or copied; the release id and its six
# revisions are verified (existence, checksum, signature, fixtures) and then recorded:
#   <repo>/releases/<env>/<releaseId>.json          immutable promotion record (the manifest + promotion metadata)
#   <repo>/releases/<env>/events/<ts>-promote-<releaseId>.json   append-only timeline (one file per event)
# and the application repository's release file (deploy/helm/governance/releases/<env>.json) is written for the PR
# (--app-repo DIR): merging that PR is what rolls the pods.
#   promote.sh --release-id rel-xxx --environment dev|staging|production --verification-key PUB
#              [--source candidates|dev|staging] [--app-repo DIR] [--app-release-dir deploy/helm/governance/releases]
#              [--actor NAME] [--reason TEXT] [--rollback]
# Environment: ARTIFACTORY_URL, ARTIFACTORY_REPOSITORY, ARTIFACTORY_TOKEN (deploy-without-overwrite on releases/**).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
release_id="" env="" pubkey="" source="candidates" app_repo="" app_dir="deploy/helm/governance/releases" actor="${GITHUB_ACTOR:-$(whoami)}" reason="" rollback=0
while [ $# -gt 0 ]; do
  case "$1" in
    --release-id) release_id="$2"; shift 2 ;;
    --environment) env="$2"; shift 2 ;;
    --verification-key) pubkey="$2"; shift 2 ;;
    --source) source="$2"; shift 2 ;;
    --app-repo) app_repo="$2"; shift 2 ;;
    --app-release-dir) app_dir="$2"; shift 2 ;;
    --actor) actor="$2"; shift 2 ;;
    --reason) reason="$2"; shift 2 ;;
    --rollback) rollback=1; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$release_id" ] && [ -n "$env" ] && [ -n "$pubkey" ] || { echo "usage: $0 --release-id ID --environment ENV --verification-key PUB [--source candidates|dev|staging] [--app-repo DIR]" >&2; exit 2; }
case "$env" in dev|staging|production) ;; *) echo "environment must be dev, staging or production" >&2; exit 1 ;; esac
case "$source" in candidates|dev|staging) ;; *) echo "source must be candidates, dev or staging" >&2; exit 1 ;; esac
[ "$source" != "$env" ] || { echo "source and target environment are the same" >&2; exit 1; }
: "${ARTIFACTORY_URL:?}" "${ARTIFACTORY_REPOSITORY:?}" "${ARTIFACTORY_TOKEN:?}"
base="${ARTIFACTORY_URL%/}/${ARTIFACTORY_REPOSITORY}"
auth=(-H "Authorization: Bearer ${ARTIFACTORY_TOKEN}")
mkdir -p "dist/promote"
work="$(mktemp -d "dist/promote/$env-$release_id.XXXXXX")"   # fresh per run: a stale artifact of the same revision must never be reused
kind=promote; [ $rollback -eq 1 ] && kind=rollback

echo ">> 1. source manifest releases/$source/$release_id.json"
src_url="$base/releases/$source/$release_id.json"
curl -sSf "${auth[@]}" -o "$work/source.json" "$src_url" || { echo "release $release_id is not in releases/$source (never published or never promoted there)"; exit 1; }
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); assert m["releaseId"] == sys.argv[2], "manifest id %s != %s" % (m["releaseId"], sys.argv[2])' "$work/source.json" "$release_id"
if [ $rollback -eq 1 ]; then
  echo ">> 1b. rollback target must have been promoted to $env and verified there before"
  for p in "releases/$env/$release_id.json" "releases/$env/verified/$release_id.json"; do
    code="$(curl -sS -o /dev/null -w '%{http_code}' -I "${auth[@]}" "$base/$p")"
    [ "$code" = 200 ] && echo "   ok   $p" || { echo "   $p is missing ($code): $release_id was never promoted and verified in $env; rollback refused"; exit 1; }
  done
fi

echo ">> 2. artifacts exist, checksums match, signatures verify, fixtures pass (no rebuild)"
ARTIFACTORY_TOKEN="$ARTIFACTORY_TOKEN" scripts/verify-artifacts.sh --manifest "$work/source.json" --verification-key "$pubkey" --artifacts "$work/artifacts"

echo ">> 3. promotion record and event"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
event_id="$ts-$(python3 -c 'import secrets; print(secrets.token_hex(3))')"
python3 - "$work/source.json" "$work/record.json" "$work/event.json" "$env" "$source" "$actor" "$reason" "$ts" "$kind" "$release_id" <<'PY'
import json, sys
src, rec, ev, env, source, actor, reason, ts, kind, rid = sys.argv[1:11]
m = json.load(open(src))
m["source"] = "promotion"
m["promotion"] = {"environment": env, "from": source, "promotedAt": ts, "promotedBy": actor, "kind": kind, "reason": reason}
json.dump(m, open(rec, "w"), indent=2)
json.dump({"event": kind, "environment": env, "releaseId": rid, "from": source, "at": ts, "by": actor, "reason": reason,
           "bundles": {n: b["revision"] for n, b in m["bundles"].items()}}, open(ev, "w"), indent=2)
PY
if [ $rollback -eq 0 ]; then
  code="$(curl -sS -o /dev/null -w '%{http_code}' -I "${auth[@]}" "$base/releases/$env/$release_id.json")"
  if [ "$code" = 200 ]; then echo "   releases/$env/$release_id.json already exists: $release_id was promoted to $env before (records are immutable); re-run Workflow D or roll back instead"; exit 1; fi
  curl -sS --fail-with-body -o /dev/null -X PUT "${auth[@]}" -T "$work/record.json" "$base/releases/$env/$release_id.json" || { echo "   PUT releases/$env/$release_id.json failed"; exit 1; }
  echo "   PUT releases/$env/$release_id.json"
fi
curl -sS --fail-with-body -o /dev/null -X PUT "${auth[@]}" -T "$work/event.json" "$base/releases/$env/events/$event_id-$kind-$release_id.json" || { echo "   event PUT failed"; exit 1; }
echo "   PUT releases/$env/events/$event_id-$kind-$release_id.json"

if [ -n "$app_repo" ]; then
  echo ">> 4. application repository release file ($app_repo/$app_dir/$env.json)"
  mkdir -p "$app_repo/$app_dir"
  python3 - "$work/record.json" "$app_repo/$app_dir/$env.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
out = {"releaseId": m["releaseId"], "createdAt": m.get("createdAt"), "gitSha": m.get("gitSha"), "contractVersion": m.get("contractVersion"),
       "opaVersion": m.get("opaVersion"), "source": "promotion", "previousReleaseId": m.get("previousReleaseId"),
       "bundles": {n: {"revision": b["revision"], "sha256": b["sha256"]} for n, b in m["bundles"].items()}}
json.dump(out, open(sys.argv[2], "w"), indent=2); open(sys.argv[2], "a").write("\n")
print("   wrote", sys.argv[2])
PY
fi
echo "$kind OK: $release_id -> $env (record $base/releases/$env/$release_id.json)"
