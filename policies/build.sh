#!/usr/bin/env bash
# Local build entry point kept at the repository root for policy_kafka's tooling (PlatformContainers, upload-release.sh).
exec "$(dirname "${BASH_SOURCE[0]}")/scripts/build.sh" "$@"
