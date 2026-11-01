#!/usr/bin/env bash
# Prints an access token for one of the mock OIDC clients (dev-1 | approver-1 | admin-1 | outsider).
# The token is requested from inside the compose network so its issuer matches what Governance validates.
set -euo pipefail
CLIENT="${1:-dev-1}"
cd "$(dirname "$0")/.."
NETWORK="$(docker compose ps -q mock-oidc | xargs docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -sS \
  --data "grant_type=client_credentials&client_id=${CLIENT}&client_secret=secret&scope=governance" \
  http://mock-oidc:8080/default/token \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
