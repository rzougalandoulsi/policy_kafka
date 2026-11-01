#!/usr/bin/env bash
# Runs the Rego unit tests over all six bundles with verbose output (OPA pinned by OPA_VERSION, Docker when no binary).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
scripts/check-roots.sh
scripts/check-lib-sync.sh
scripts/opa.sh check --strict bundles tests --ignore fixtures --ignore integration
scripts/opa.sh test bundles tests --ignore fixtures --ignore integration -v "$@"
