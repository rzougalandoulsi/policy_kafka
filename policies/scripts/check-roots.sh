#!/usr/bin/env bash
# Bundle roots: the bundle set is exactly the six expected names, every bundle declares exactly one root
# governance/<bundle>, no root is a prefix of another, every package of a bundle lives under its root, and a bundle
# only imports its own packages (Rego functions do not resolve across bundles; core reaches the domain bundles by
# dynamic reference, never by import).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
status=0
expected="changes core manifests naming quotas resources"
found=$(ls bundles | sort | tr '\n' ' ' | sed 's/ $//')
if [ "$found" != "$expected" ]; then echo "bundle set mismatch: found '$found', expected '$expected'"; status=1; fi
all_roots=""
for dir in bundles/*/; do
  name=$(basename "$dir")
  if [ ! -f "$dir/.manifest" ]; then echo "$name: .manifest missing"; status=1; continue; fi
  roots=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(" ".join(m.get("roots",[])))' "$dir/.manifest")
  if [ "$roots" != "governance/$name" ]; then echo "$name: roots must be exactly [governance/$name], got [$roots]"; status=1; fi
  all_roots="$all_roots $roots"
  while IFS= read -r pkg; do
    case "$pkg" in
      "governance.$name"|"governance.$name".*) ;;
      *) echo "$name: package $pkg is outside root governance/$name"; status=1 ;;
    esac
  done < <(grep -h '^package ' "$dir"*.rego | awk '{print $2}')
  while IFS= read -r imp; do
    case "$imp" in
      "data.governance.$name"|"data.governance.$name".*) ;;
      *) echo "$name: cross-bundle import $imp is not allowed"; status=1 ;;
    esac
  done < <(grep -h '^import ' "$dir"*.rego | awk '{print $2}')
done
# Pairwise: no root may be a prefix of another root (OPA would refuse to activate the set).
for a in $all_roots; do
  for b in $all_roots; do
    if [ "$a" != "$b" ]; then
      case "$b" in "$a"/*) echo "root $a is a prefix of root $b"; status=1 ;; esac
    fi
  done
done
[ $status -eq 0 ] && echo "roots and imports OK"
exit $status
