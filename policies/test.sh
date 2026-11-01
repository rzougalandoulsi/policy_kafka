#!/usr/bin/env bash
# Runs the Rego unit tests with verbose output. Requires Docker.
set -euo pipefail
cd "$(dirname "$0")"
OPA_IMAGE="${OPA_IMAGE:-openpolicyagent/opa:1.22.0}"
docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" check --strict governance
docker run --rm -v "$PWD:/w" -w /w "$OPA_IMAGE" test governance -v "$@"
