#!/usr/bin/env bash
# Uploads the artifacts of a candidate release to Artifactory (Workflow B step 6 and 7) with the immutability
# pre-check: every target path is HEAD-checked first and the whole publish fails when any already exists. The
# server side must enforce the same rule ("deploy without overwrite" permission); this script never relies on it.
#   publish.sh --manifest FILE --artifacts DIR [--dry-run]
# Environment (all [A] inputs, never written to the repository):
#   ARTIFACTORY_URL          base URL, e.g. https://artifactory.example.com/artifactory
#   ARTIFACTORY_REPOSITORY   generic repository key, e.g. opa-bundles
#   ARTIFACTORY_TOKEN        short-lived access token (JFrog OIDC in Actions, scoped expiring token as fallback)
# Layout: <repo>/<bundle>/<revision>/<bundle>-<revision>.tar.gz, .tar.gz.sha256, <bundle>-<revision>.signatures.json
#         <repo>/releases/candidates/<releaseId>.json
# Properties git.sha, contract.version, opa.version, release.id are set through the storage API after each upload.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
manifest="" artifacts="" dry_run=0
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest) manifest="$2"; shift 2 ;;
    --artifacts) artifacts="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$manifest" ] && [ -n "$artifacts" ] || { echo "usage: $0 --manifest FILE --artifacts DIR [--dry-run]" >&2; exit 2; }
: "${ARTIFACTORY_URL:?ARTIFACTORY_URL is required}"
: "${ARTIFACTORY_REPOSITORY:?ARTIFACTORY_REPOSITORY is required}"
: "${ARTIFACTORY_TOKEN:?ARTIFACTORY_TOKEN is required}"
base="${ARTIFACTORY_URL%/}/${ARTIFACTORY_REPOSITORY}"
auth=(-H "Authorization: Bearer ${ARTIFACTORY_TOKEN}")
release_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$manifest")"
opa_version="$(tr -d '[:space:]' < OPA_VERSION)"
sha256() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | awk '{print $1}'; }

# Upload plan: "<local file>\t<remote path>\t<bundle>\t<revision>\t<contract>" for bundles whose url points at this
# repository and whose artifact was built in this run (the others were published earlier and are only referenced).
plan=()
while IFS=$'\t' read -r name revision contract url; do
  case "$url" in "$base"/*) ;; *) echo "skip $name $revision (not published by this run: $url)"; continue ;; esac
  f="$artifacts/$revision.tar.gz"
  if [ ! -f "$f.sha256" ] || [ ! -f "$artifacts/$revision.signatures.json" ]; then echo "skip $name $revision (previously published, no local build)"; continue; fi
  plan+=("$f"$'\t'"$name/$revision/$revision.tar.gz"$'\t'"$name"$'\t'"$revision"$'\t'"$contract")
  plan+=("$f.sha256"$'\t'"$name/$revision/$revision.tar.gz.sha256"$'\t'"$name"$'\t'"$revision"$'\t'"$contract")
  plan+=("$artifacts/$revision.signatures.json"$'\t'"$name/$revision/$revision.signatures.json"$'\t'"$name"$'\t'"$revision"$'\t'"$contract")
done < <(python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
for n, b in m["bundles"].items():
    print("\t".join([n, b["revision"], str(b.get("contractVersion", "")), str(b.get("url", ""))]))
' "$manifest")
plan+=("$manifest"$'\t'"releases/candidates/$release_id.json"$'\t'"release"$'\t'"$release_id"$'\t'"")
[ ${#plan[@]} -gt 1 ] || { echo "nothing to upload for $release_id (no artifact built in this run)" >&2; exit 1; }

echo ">> immutability pre-check (${#plan[@]} paths under $base)"
conflicts=0
for entry in "${plan[@]}"; do
  remote="$(cut -f2 <<< "$entry")"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -I "${auth[@]}" "$base/$remote")"
  case "$code" in
    404) ;;
    200) echo "   EXISTS $remote (revisions are immutable, build a new one)"; conflicts=$((conflicts + 1)) ;;
    *) echo "   HEAD $remote returned $code"; conflicts=$((conflicts + 1)) ;;
  esac
done
[ $conflicts -eq 0 ] || { echo "publish refused: $conflicts path(s) already exist or could not be checked"; exit 1; }
if [ $dry_run -eq 1 ]; then echo "dry run: would upload"; printf '   %s\n' "${plan[@]}" | cut -f2; exit 0; fi

echo ">> upload"
for entry in "${plan[@]}"; do
  IFS=$'\t' read -r local remote name revision contract <<< "$entry"
  digest="$(sha256 "$local")"
  curl -sS --fail-with-body -o /dev/null -X PUT "${auth[@]}" -H "X-Checksum-Sha256: $digest" -T "$local" "$base/$remote" \
    || { echo "upload failed: $remote"; exit 1; }
  props="git.sha=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["gitSha"])' "$manifest");release.id=$release_id;opa.version=$opa_version"
  [ -n "$contract" ] && props="$props;contract.version=$contract"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "${auth[@]}" "${ARTIFACTORY_URL%/}/api/storage/${ARTIFACTORY_REPOSITORY}/$remote?properties=$props")"
  [ "$code" = 204 ] || { echo "setting properties on $remote returned $code"; exit 1; }
  echo "   PUT $remote sha256=$digest"
done

echo ">> verify by download"
tmp="$(mktemp -d)"
for entry in "${plan[@]}"; do
  IFS=$'\t' read -r local remote _ <<< "$entry"
  curl -sSf "${auth[@]}" -o "$tmp/check" "$base/$remote"
  [ "$(sha256 "$tmp/check")" = "$(sha256 "$local")" ] || { echo "verification failed: $remote differs after upload"; exit 1; }
done
echo "published release $release_id: $(( (${#plan[@]} - 1) / 3 )) bundle(s) + candidate manifest -> $base/releases/candidates/$release_id.json"
