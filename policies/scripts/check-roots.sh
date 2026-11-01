#!/usr/bin/env bash
# Every bundle must declare exactly one root, governance/<bundle>, and every package in it must live under that root.
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
expected="changes core manifests naming quotas resources"
found=$(ls bundles | sort | tr '\n' ' ' | sed 's/ $//')
if [ "$found" != "$expected" ]; then echo "bundle set mismatch: found '$found', expected '$expected'"; status=1; fi
for dir in bundles/*/; do
  name=$(basename "$dir")
  roots=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(" ".join(m.get("roots",[])))' "$dir/.manifest")
  if [ "$roots" != "governance/$name" ]; then echo "$name: roots must be exactly [governance/$name], got [$roots]"; status=1; fi
  while IFS= read -r pkg; do
    case "$pkg" in
      "governance.$name"|"governance.$name".*) ;;
      *) echo "$name: package $pkg is outside root governance/$name"; status=1 ;;
    esac
  done < <(grep -h '^package ' "$dir"*.rego | awk '{print $2}')
  # Cross-bundle imports: a bundle may only import its own packages (functions do not resolve across bundles).
  while IFS= read -r imp; do
    case "$imp" in
      "data.governance.$name"|"data.governance.$name".*) ;;
      *) echo "$name: cross-bundle import $imp is not allowed"; status=1 ;;
    esac
  done < <(grep -h '^import ' "$dir"*.rego | awk '{print $2}')
done
[ $status -eq 0 ] && echo "roots and imports OK"
exit $status
