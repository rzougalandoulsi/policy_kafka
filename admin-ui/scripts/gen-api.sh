#!/usr/bin/env bash
# Regenerates src/api/schema.d.ts from a running Governance instance (default http://localhost:8080).
# The hand-written src/api/types.ts mirrors the Java records; this generated file is the drift detector:
# compare it after backend changes. Usage: scripts/gen-api.sh [base-url]
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${1:-http://localhost:8080}"
curl -fsS "$BASE/v3/api-docs" > openapi.json
pnpm exec openapi-typescript openapi.json -o src/api/schema.d.ts
echo "wrote openapi.json and src/api/schema.d.ts from $BASE"
