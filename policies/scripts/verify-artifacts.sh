#!/usr/bin/env bash
# Verifies that every artifact of a release manifest exists in Artifactory unchanged and that the set loads under
# signature verification (no rebuild): download each tarball, compare its sha256 with the manifest, then run the
# smoke (opa run --server with --verification-key, ready == true, fixtures). Used by Workflows C, D and E.
#   verify-artifacts.sh --manifest FILE --verification-key PUBLIC.pem [--artifacts DIR] [--skip-smoke]
# Environment: ARTIFACTORY_TOKEN (read-only).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
manifest="" pubkey="" artifacts="" smoke=1
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest) manifest="$2"; shift 2 ;;
    --verification-key) pubkey="$2"; shift 2 ;;
    --artifacts) artifacts="$2"; shift 2 ;;
    --skip-smoke) smoke=0; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$manifest" ] || { echo "usage: $0 --manifest FILE --verification-key PUB [--artifacts DIR] [--skip-smoke]" >&2; exit 2; }
[ $smoke -eq 0 ] || [ -n "$pubkey" ] || { echo "--verification-key is required unless --skip-smoke" >&2; exit 2; }
if [ -z "$artifacts" ]; then mkdir -p dist/verify; artifacts="$(mktemp -d "dist/verify/$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$manifest").XXXXXX")"; fi
mkdir -p "$artifacts"
echo ">> HEAD every artifact"
status=0
while IFS=$'\t' read -r name revision url; do
  [ -n "$url" ] && [ "$url" != None ] || { echo "   $name: manifest has no url"; status=1; continue; }
  code="$(curl -sS -o /dev/null -w '%{http_code}' -I ${ARTIFACTORY_TOKEN:+-H "Authorization: Bearer $ARTIFACTORY_TOKEN"} "$url")"
  [ "$code" = 200 ] && echo "   ok   $name $revision" || { echo "   MISSING $name $revision ($code) $url"; status=1; }
done < <(python3 -c '
import json, sys
for n, b in json.load(open(sys.argv[1]))["bundles"].items(): print("\t".join([n, b["revision"], str(b.get("url"))]))' "$manifest")
[ $status -eq 0 ] || { echo "verify-artifacts: artifacts missing"; exit 1; }
echo ">> download and compare checksums"
scripts/assemble-release.sh --manifest "$manifest" --artifacts "$artifacts"
if [ $smoke -eq 1 ]; then
  echo ">> signatures and behaviour (opa run --server with the verification key)"
  tests/integration/smoke.sh --manifest "$manifest" --artifacts "$artifacts" --verification-key "$pubkey" --port "${SMOKE_PORT:-18195}"
fi
echo "verify-artifacts OK: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$manifest")"
