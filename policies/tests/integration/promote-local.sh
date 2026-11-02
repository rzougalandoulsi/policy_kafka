#!/usr/bin/env bash
# Local proof of Workflows C, D (artifact half) and E against the Artifactory stand-in (plan Phase 5 acceptance):
#   1. publish two candidates (Workflow B steps) so there is something to promote and to roll back to;
#   2. promote the first to dev: artifacts re-verified without rebuild, immutable record + event written, the
#      application repository's release file rendered; a second promotion of the same release is refused;
#   3. promoting a never-published id is refused; promoting to staging from candidates then from dev;
#   4. Workflow D's artifact half (verify-artifacts) and the verified marker; a rollback to an unverified release is
#      refused, a rollback to the verified one writes the event and the release file;
#   5. no artifact changed through all of it (checksums equal to the candidate manifests).
# Needs Docker. The cluster half of Workflow D (verify-active.sh) needs oc and a namespace and is not run here.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
work="$(mktemp -d "${TMPDIR:-/tmp}/promote-local.XXXXXX")"
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
ARTIFACTORY_URL="$($standin up "$work/artifactory" 18083)"; export ARTIFACTORY_URL
base_url="$ARTIFACTORY_URL/$ARTIFACTORY_REPOSITORY"
sha="$(git rev-parse --short HEAD)"; day="$(date -u +%Y%m%d)"
run_dir="dist/promote-local/$(date -u +%Y%m%dT%H%M%S)"
rid() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$1"; }
head_code() { curl -s -o /dev/null -w '%{http_code}' -I -H "Authorization: Bearer $ARTIFACTORY_TOKEN" "$base_url/$1"; }

echo "### 1. two candidates"
scripts/build-release.sh --suffix "$day.1-$sha" --signing-key "$work/signing.pem" --bundles all --out "$run_dir/c1" >/dev/null
scripts/release-manifest.sh --artifacts "$run_dir/c1" --out "$run_dir/c1/candidate.json" --base-url "$base_url" --source candidate
scripts/publish.sh --manifest "$run_dir/c1/candidate.json" --artifacts "$run_dir/c1" >/dev/null
scripts/build-release.sh --suffix "$day.2-$sha" --signing-key "$work/signing.pem" --bundles quotas --out "$run_dir/c2" >/dev/null
scripts/release-manifest.sh --artifacts "$run_dir/c2" --out "$run_dir/c2/candidate.json" --previous "$run_dir/c1/candidate.json" --base-url "$base_url" --source candidate
ARTIFACTORY_TOKEN="$ARTIFACTORY_TOKEN" scripts/assemble-release.sh --manifest "$run_dir/c2/candidate.json" --artifacts "$run_dir/c2" >/dev/null
scripts/publish.sh --manifest "$run_dir/c2/candidate.json" --artifacts "$run_dir/c2" >/dev/null
r1="$(rid "$run_dir/c1/candidate.json")"; r2="$(rid "$run_dir/c2/candidate.json")"
pass "candidates $r1 and $r2 published"

echo "### 2. promote $r1 to dev"
app="$work/app-repo"
SMOKE_PORT=18196 scripts/promote.sh --release-id "$r1" --environment dev --verification-key "$work/signing.pub" --app-repo "$app" --actor tester --reason "first dev promotion"
[ "$(head_code "releases/dev/$r1.json")" = 200 ] && pass "promotion record written" || fail "promotion record missing"
[ "$(rid "$app/deploy/helm/governance/releases/dev.json")" = "$r1" ] && pass "application release file points at $r1" || fail "release file wrong"
python3 - "$app/deploy/helm/governance/releases/dev.json" <<'PY' && pass "release file has the shape Governance and Helm read"
import json, sys
m = json.load(open(sys.argv[1]))
assert set(m["bundles"]) == {"core","manifests","naming","quotas","resources","changes"}
assert all({"revision","sha256"} <= set(b) for b in m["bundles"].values())
PY
if SMOKE_PORT=18196 scripts/promote.sh --release-id "$r1" --environment dev --verification-key "$work/signing.pub" > "$work/repromote.log" 2>&1; then fail "second promotion of $r1 to dev succeeded"; fi
grep -q "already exists" "$work/repromote.log" && pass "promoting the same release twice is refused (immutable record)" || { cat "$work/repromote.log"; fail "unexpected re-promotion failure"; }

echo "### 3. unknown release and chained promotion"
if SMOKE_PORT=18196 scripts/promote.sh --release-id rel-000000000000 --environment dev --verification-key "$work/signing.pub" > "$work/unknown.log" 2>&1; then fail "unknown release promoted"; fi
grep -q "not in releases/candidates" "$work/unknown.log" && pass "a never-published release cannot be promoted" || { cat "$work/unknown.log"; fail "unexpected failure"; }
if SMOKE_PORT=18196 scripts/promote.sh --release-id "$r2" --environment staging --source dev --verification-key "$work/signing.pub" > "$work/stagingfromdev.log" 2>&1; then fail "$r2 promoted to staging from dev although never in dev"; fi
pass "staging only accepts releases that reached dev (when sourced from dev)"
SMOKE_PORT=18196 scripts/promote.sh --release-id "$r2" --environment dev --verification-key "$work/signing.pub" --app-repo "$app" --actor tester --reason "second dev promotion" >/dev/null
SMOKE_PORT=18196 scripts/promote.sh --release-id "$r2" --environment staging --source dev --verification-key "$work/signing.pub" --app-repo "$app" --actor tester --reason "to staging" >/dev/null
[ "$(rid "$app/deploy/helm/governance/releases/staging.json")" = "$r2" ] && pass "$r2 promoted dev -> staging" || fail "staging file wrong"

echo "### 4. Workflow D artifact half, verified marker, rollback rules"
curl -sSf -H "Authorization: Bearer $ARTIFACTORY_TOKEN" -o "$work/dev-r1.json" "$base_url/releases/dev/$r1.json"
SMOKE_PORT=18197 scripts/verify-artifacts.sh --manifest "$work/dev-r1.json" --verification-key "$work/signing.pub" --artifacts "$run_dir/verify-r1" >/dev/null && pass "verify-artifacts: $r1 artifacts exist, checksums match, signatures verify"
if SMOKE_PORT=18197 scripts/promote.sh --rollback --release-id "$r1" --environment dev --verification-key "$work/signing.pub" > "$work/rb-unverified.log" 2>&1; then fail "rollback to an unverified release succeeded"; fi
grep -q "never promoted and verified" "$work/rb-unverified.log" && pass "rollback refuses a release Workflow D never verified" || { cat "$work/rb-unverified.log"; fail "unexpected rollback failure"; }
scripts/mark-verified.sh --release-id "$r1" --environment dev --run local >/dev/null
[ "$(head_code "releases/dev/verified/$r1.json")" = 200 ] && pass "verified marker written" || fail "marker missing"
scripts/mark-verified.sh --release-id "$r1" --environment dev --run local | grep -q "already recorded" && pass "second verification keeps the marker immutable"
SMOKE_PORT=18197 scripts/promote.sh --rollback --release-id "$r1" --environment dev --verification-key "$work/signing.pub" --app-repo "$app" --actor tester --reason "incident 42" >/dev/null
[ "$(rid "$app/deploy/helm/governance/releases/dev.json")" = "$r1" ] && pass "rollback points the dev release file back at $r1" || fail "rollback file wrong"
ls "$work/artifactory/opa-bundles/releases/dev/events/" | grep -q "rollback-$r1" && pass "rollback event recorded" || fail "rollback event missing"

echo "### 5. nothing mutated"
python3 - "$run_dir/c1/candidate.json" "$run_dir/c2/candidate.json" "$work/artifactory/opa-bundles" <<'PY' && pass "every artifact still has its candidate checksum"
import hashlib, json, os, sys
root = sys.argv[3]
for m in sys.argv[1:3]:
    for n, b in json.load(open(m))["bundles"].items():
        f = os.path.join(root, n, b["revision"], b["revision"] + ".tar.gz")
        assert hashlib.sha256(open(f, "rb").read()).hexdigest() == b["sha256"], f
PY
echo
echo "promote-local OK: records under $work/artifactory/opa-bundles/releases (dev: $r1, $r2; staging: $r2; rollback dev -> $r1)"
