#!/usr/bin/env bash
# Runs the pinned OPA (OPA_VERSION): the binary on PATH when its version matches, otherwise the Docker image.
# Every script calls OPA through this wrapper so the local flow and the workflows use one version.
# Paths passed to OPA must be relative to the repository root (the Docker mode mounts that root at /w).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/OPA_VERSION")"
native=0
if command -v opa >/dev/null 2>&1 && opa version 2>/dev/null | grep -q "^Version: ${VERSION}$"; then native=1; fi
[ -n "${OPA_BIN:-}" ] && native=1
if [ "${1:-}" = "--mode" ]; then [ $native -eq 1 ] && echo native || echo docker; exit 0; fi
if command -v opa >/dev/null 2>&1 && opa version 2>/dev/null | grep -q "^Version: ${VERSION}$"; then
  exec opa "$@"
fi
if [ -n "${OPA_BIN:-}" ]; then
  exec "$OPA_BIN" "$@"
fi
IMAGE="${OPA_IMAGE:-openpolicyagent/opa:${VERSION}}"
tty_flag=""
[ -t 0 ] && tty_flag="-i"
exec docker run --rm $tty_flag ${OPA_DOCKER_ARGS:-} -v "$ROOT:/w" -w /w "$IMAGE" "$@"
