#!/usr/bin/env bash
# Prints the bundle names (one per line, sorted) whose sources changed.
#   changed-bundles.sh --since <ref>      changes between merge-base(<ref>, HEAD) and HEAD
#   changed-bundles.sh --all              every bundle
#   changed-bundles.sh --bundles a,b,all  an explicit list (validated), used by workflow_dispatch
# A change to OPA_VERSION or opa.sha256 means a new compiler: every bundle is reported.
# Prints nothing when nothing changed (callers decide whether that is an error).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ALL="changes core manifests naming quotas resources"
mode="${1:---all}"
arg="${2:-}"
emit() { tr ' ' '\n' <<< "$1" | sed '/^$/d' | sort -u; }
case "$mode" in
  --all) emit "$ALL" ;;
  --bundles)
    [ -n "$arg" ] || { echo "--bundles needs a list" >&2; exit 2; }
    if [ "$arg" = all ]; then emit "$ALL"; exit 0; fi
    chosen=""
    for b in ${arg//,/ }; do
      case " $ALL " in *" $b "*) chosen="$chosen $b" ;; *) echo "unknown bundle '$b'" >&2; exit 2 ;; esac
    done
    emit "$chosen" ;;
  --since)
    [ -n "$arg" ] || { echo "--since needs a git ref" >&2; exit 2; }
    base=$(git merge-base "$arg" HEAD 2>/dev/null) || { echo "ref '$arg' unknown, reporting every bundle" >&2; emit "$ALL"; exit 0; }
    files=$(git diff --name-only "$base" HEAD)
    if grep -qE '^(OPA_VERSION|opa\.sha256)$' <<< "$files"; then emit "$ALL"; exit 0; fi
    emit "$(grep -E '^bundles/[^/]+/' <<< "$files" | cut -d/ -f2 | tr '\n' ' ' || true)" ;;
  *) echo "usage: $0 --since <ref> | --all | --bundles <a,b|all>" >&2; exit 2 ;;
esac
