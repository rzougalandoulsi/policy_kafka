#!/usr/bin/env bash
# Runs the Rego unit tests over all six bundles with verbose output. Requires Docker.
set -euo pipefail
cd "$(dirname "$0")"
OPA_IMAGE="${OPA_IMAGE:-openpolicyagent/opa:1.22.0}"
scripts/check-roots.sh
scripts/check-lib-sync.sh
docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" check --strict bundles tests
docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" test bundles tests -v "$@"
