#!/usr/bin/env bash
# Rules and bundle inventory page: one self-contained HTML file generated from the catalogue, the Rego sources, the
# tests, the fixtures and the environments' release files (scripts/lib/inventory.py has the details).
#   inventory.sh [--out FILE] [--release-files DIR] [--check]
# Default output: ../docs/policy-inventory.html while the policies live inside policy_kafka (the directory exists),
# otherwise dist/policy-inventory.html. --check regenerates in memory and fails when the committed page differs, so a
# catalogue or rule change without a regenerated page cannot merge (Workflow A); it is a no-op when the default
# output directory does not exist (after the extraction, until the page finds a new home).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="" release_dir="" check=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --release-files) release_dir="$2"; shift 2 ;;
    --check) check=1; shift ;;
    *) echo "usage: $0 [--out FILE] [--release-files DIR] [--check]" >&2; exit 2 ;;
  esac
done
if [ -z "$out" ]; then
  if [ -d ../docs ]; then out="../docs/policy-inventory.html"
  elif [ $check -eq 1 ]; then echo "inventory: no docs directory next to the repository; nothing to check"; exit 0
  else out="dist/policy-inventory.html"; fi
fi
[ -n "$release_dir" ] || { [ -d ../deploy/helm/governance/releases ] && release_dir=../deploy/helm/governance/releases || release_dir=""; }
mode=write; [ $check -eq 1 ] && mode=check
python3 scripts/lib/inventory.py scripts/opa.sh scripts/lib/inventory-template.html "$release_dir" "$out" "$mode"
