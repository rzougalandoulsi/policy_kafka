#!/usr/bin/env bash
# The per-bundle lib.rego copies must be identical apart from their package line.
set -euo pipefail
cd "$(dirname "$0")/.."
ref=$(tail -n +2 bundles/core/lib.rego)
status=0
for f in bundles/*/lib.rego; do
  if [ "$(tail -n +2 "$f")" != "$ref" ]; then echo "$f differs from bundles/core/lib.rego"; status=1; fi
done
[ $status -eq 0 ] && echo "lib copies in sync"
exit $status
