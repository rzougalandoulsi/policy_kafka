#!/usr/bin/env bash
# Regal lint (pinned in REGAL_VERSION, configuration in .regal/config.yaml): the binary on PATH when its version
# matches, otherwise the ghcr.io/open-policy-agent/regal image.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
VERSION="$(tr -d '[:space:]' < REGAL_VERSION)"
if command -v regal >/dev/null 2>&1 && regal version 2>/dev/null | grep -q "Version: *${VERSION}\b"; then
  exec regal lint "${@:-bundles}"
fi
exec docker run --rm -v "$PWD:/w" -w /w "ghcr.io/open-policy-agent/regal:${VERSION}" lint "${@:-bundles}"
