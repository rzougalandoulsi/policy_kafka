#!/usr/bin/env bash
# Runs the nginx Artifactory stand-in in Docker.
#   standin.sh up <data dir> [port]   starts it (prints the base URL), token in STANDIN_TOKEN (default local-standin-token)
#   standin.sh down                   stops it
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
name="governance-artifactory-standin"
image="${STANDIN_IMAGE:-nginx:1.29-alpine}"
case "${1:-}" in
  up)
    data="${2:?data dir}"; port="${3:-18082}"
    mkdir -p "$data"
    docker run --rm -d --name "$name" -p "127.0.0.1:${port}:8080" \
      -e STANDIN_TOKEN="${STANDIN_TOKEN:-local-standin-token}" \
      -v "$here/nginx.conf.template:/etc/nginx/templates/default.conf.template:ro" \
      -v "$(cd "$data" && pwd):/data" "$image" >/dev/null
    for _ in $(seq 1 30); do
      curl -sf "http://127.0.0.1:${port}/health" >/dev/null 2>&1 && break
      sleep 0.3
    done
    curl -sf "http://127.0.0.1:${port}/health" >/dev/null || { docker logs "$name" 2>&1 | tail -5; exit 1; }
    echo "http://127.0.0.1:${port}/artifactory" ;;
  down) docker stop "$name" >/dev/null 2>&1 || true ;;
  *) echo "usage: $0 up <data dir> [port] | down" >&2; exit 2 ;;
esac
