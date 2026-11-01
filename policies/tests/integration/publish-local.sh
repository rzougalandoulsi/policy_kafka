#!/usr/bin/env bash
# Local proof of Workflow B against the Artifactory stand-in (plan Phase 3 acceptance):
#   1. full signed build -> candidate manifest -> assemble -> smoke -> publish;
#   2. re-publishing the same revisions is refused by the pre-check, and a direct PUT is refused by the server (409);
#   3. an incremental build of one bundle keeps the five previous revisions, downloads them back from the stand-in,
#      verifies their checksums, smokes and publishes a second candidate that references the first;
#   4. the smoke fails with a foreign verification key (signatures are really checked).
# Needs Docker. Leaves no artifacts in the repository (dist/ is ignored) and nothing on the stand-in after `down`.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
work="$(mktemp -d "${TMPDIR:-/tmp}/publish-local.XXXXXX")"
standin=tests/integration/artifactory-standin/standin.sh
export STANDIN_TOKEN="local-standin-$RANDOM$RANDOM"
export ARTIFACTORY_REPOSITORY=opa-bundles
export ARTIFACTORY_TOKEN="$STANDIN_TOKEN"
cleanup() { $standin down; }
trap cleanup EXIT
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; exit 1; }

openssl genrsa -out "$work/signing.pem" 3072 2>/dev/null
openssl rsa -in "$work/signing.pem" -pubout -out "$work/signing.pub" 2>/dev/null
openssl genrsa -out "$work/foreign.pem" 2048 2>/dev/null
openssl rsa -in "$work/foreign.pem" -pubout -out "$work/foreign.pub" 2>/dev/null
ARTIFACTORY_URL="$($standin up "$work/artifactory")"; export ARTIFACTORY_URL
base_url="$ARTIFACTORY_URL/$ARTIFACTORY_REPOSITORY"
sha="$(git rev-parse --short HEAD)"
day="$(date -u +%Y%m%d)"
run_dir="dist/publish-local/$(date -u +%Y%m%dT%H%M%S)"
out1="$run_dir/run1"; out2="$run_dir/run2"

echo "### 1. full build, candidate, smoke, publish"
scripts/build-release.sh --suffix "$day.1-$sha" --signing-key "$work/signing.pem" --bundles all --out "$out1"
scripts/release-manifest.sh --artifacts "$out1" --out "$out1/candidate.json" --base-url "$base_url" --source candidate
scripts/check-schemas.sh --release "$out1/candidate.json" >/dev/null && pass "candidate manifest matches release-manifest.schema.json"
scripts/assemble-release.sh --manifest "$out1/candidate.json" --artifacts "$out1"
tests/integration/smoke.sh --manifest "$out1/candidate.json" --artifacts "$out1" --verification-key "$work/signing.pub" --port 18191
scripts/publish.sh --manifest "$out1/candidate.json" --artifacts "$out1"
rel1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$out1/candidate.json")"
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ARTIFACTORY_TOKEN" "$base_url/core/core-$day.1-$sha/core-$day.1-$sha.tar.gz")"
[ "$code" = 200 ] && pass "artifact reachable after publish (HEAD 200)" || fail "artifact not found after publish ($code)"
code="$(curl -s -o /dev/null -w '%{http_code}' "$base_url/releases/candidates/$rel1.json")"
[ "$code" = 401 ] && pass "stand-in refuses anonymous access" || fail "anonymous access returned $code"

echo "### 2. immutability"
if scripts/publish.sh --manifest "$out1/candidate.json" --artifacts "$out1" > "$work/republish.log" 2>&1; then
  cat "$work/republish.log"; fail "re-publishing an existing revision succeeded"
fi
grep -q "EXISTS" "$work/republish.log" && pass "re-publish refused by the HEAD pre-check" || { cat "$work/republish.log"; fail "unexpected re-publish failure"; }
code="$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $ARTIFACTORY_TOKEN" -T "$out1/candidate.json" "$base_url/releases/candidates/$rel1.json")"
[ "$code" = 409 ] && pass "direct overwrite refused by the server (409)" || fail "direct overwrite returned $code"
before="$(curl -s -H "Authorization: Bearer $ARTIFACTORY_TOKEN" "$base_url/releases/candidates/$rel1.json" | shasum -a 256)"
after="$(shasum -a 256 < "$out1/candidate.json")"
[ "$before" = "$after" ] && pass "stored candidate unchanged" || fail "stored candidate changed"

echo "### 3. incremental publish (naming only) referencing the previous candidate"
scripts/build-release.sh --suffix "$day.2-$sha" --signing-key "$work/signing.pem" --bundles naming --out "$out2"
scripts/release-manifest.sh --artifacts "$out2" --out "$out2/candidate.json" --previous "$out1/candidate.json" --base-url "$base_url" --source candidate
scripts/assemble-release.sh --manifest "$out2/candidate.json" --artifacts "$out2"
[ "$(ls "$out2"/*.tar.gz | wc -l | tr -d ' ')" = 6 ] && pass "five previous artifacts downloaded and verified" || fail "assembled set incomplete"
tests/integration/smoke.sh --manifest "$out2/candidate.json" --artifacts "$out2" --verification-key "$work/signing.pub" --port 18192
scripts/publish.sh --manifest "$out2/candidate.json" --artifacts "$out2"
rel2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$out2/candidate.json")"
prev="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["previousReleaseId"])' "$out2/candidate.json")"
[ "$prev" = "$rel1" ] && [ "$rel2" != "$rel1" ] && pass "second candidate $rel2 references $rel1" || fail "release chaining broken ($prev, $rel2)"
kept="$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(sum(1 for n,b in m["bundles"].items() if b["revision"].endswith(".1-'"$sha"'")))' "$out2/candidate.json")"
[ "$kept" = 5 ] && pass "five bundles kept their first revision" || fail "expected 5 kept revisions, got $kept"

echo "### 4. foreign verification key"
if tests/integration/smoke.sh --manifest "$out2/candidate.json" --artifacts "$out2" --verification-key "$work/foreign.pub" --port 18193 > "$work/foreign.log" 2>&1; then
  fail "smoke passed with a foreign key"
fi
pass "smoke refuses artifacts under a foreign verification key"
echo
echo "publish-local OK: $rel1 then $rel2 on $base_url (data in $work/artifactory)"
