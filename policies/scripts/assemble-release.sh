#!/usr/bin/env bash
# Makes DIR hold the six artifacts of a release manifest: bundles built in this run are already there, the others
# are downloaded from their manifest url (read-only token) and every file is checked against the manifest sha256.
#   assemble-release.sh --manifest FILE --artifacts DIR        (ARTIFACTORY_TOKEN for the download)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
manifest="" artifacts=""
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest) manifest="$2"; shift 2 ;;
    --artifacts) artifacts="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$manifest" ] && [ -n "$artifacts" ] || { echo "usage: $0 --manifest FILE --artifacts DIR" >&2; exit 2; }
mkdir -p "$artifacts"
sha256() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | awk '{print $1}'; }
status=0
while IFS=$'\t' read -r name revision expected url; do
  target="$artifacts/$revision.tar.gz"
  if [ ! -f "$target" ]; then
    [ -n "$url" ] && [ "$url" != null ] || { echo "$name: $target missing and the manifest has no url"; status=1; continue; }
    echo ">> fetch $name $revision"
    curl -sSf --retry 3 ${ARTIFACTORY_TOKEN:+-H "Authorization: Bearer $ARTIFACTORY_TOKEN"} -o "$target" "$url" || { echo "$name: download failed: $url"; status=1; continue; }
  fi
  actual="$(sha256 "$target")"
  if [ "$actual" != "$expected" ]; then echo "$name: sha256 mismatch for $target (manifest $expected, file $actual)"; status=1; else echo "ok   $name $revision"; fi
done < <(python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
for n, b in m["bundles"].items():
    print("\t".join([n, b["revision"], b["sha256"], str(b.get("url", ""))]))
' "$manifest")
[ $status -eq 0 ] && echo "release assembled in $artifacts"
exit $status
