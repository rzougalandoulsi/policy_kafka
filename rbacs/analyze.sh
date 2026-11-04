#!/usr/bin/env bash
# Read-only Confluent Cloud RBAC optimization analyzer.
# Validates dependencies and paths, then delegates to `python3 -m analyzer`.
# Compatible with Bash 3.2+ (macOS) and Bash 5 (WSL/Linux).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'USAGE'
Usage: ./analyze.sh --input FILE [--config FILE] [--output DIR]
                    [--rules RB001,RB002,...] [--format terminal|json|csv|all]
                    [--verbose] [--help]

Analyzes an exported Confluent Cloud RBAC inventory and reports redundant
bindings and consolidation opportunities. Never modifies Confluent Cloud.

Options:
  --input FILE     exported RBAC inventory (JSON)            [required]
  --config FILE    analyzer configuration                    [default: config/analyzer.json]
  --output DIR     directory for report.json / recommendations.csv [default: ./output]
  --rules IDS      comma-separated rule ids to run           [default: rules enabled in config]
  --format FMT     terminal, json, csv or all; repeatable    [default: all]
  --verbose        list findings, skipped records, and all warnings
  --help           show this help

Exit codes: 0 success, 1 invalid input or config, 2 usage error, 3 unexpected failure.
USAGE
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit "${2:-1}"
}

input=""
config=""
output="./output"
prev=""
for arg in "$@"; do
  case "$arg" in
    --help|-h) usage; exit 0 ;;
    --input=*) input="${arg#*=}" ;;
    --config=*) config="${arg#*=}" ;;
    --output=*) output="${arg#*=}" ;;
  esac
  case "$prev" in
    --input) input="$arg" ;;
    --config) config="$arg" ;;
    --output) output="$arg" ;;
  esac
  prev="$arg"
done

command -v python3 >/dev/null 2>&1 || die "python3 is required but was not found on PATH"
if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' 2>/dev/null; then
  die "python3 >= 3.9 is required (found: $(python3 --version 2>&1))"
fi

if [ -z "$input" ]; then
  usage >&2
  die "--input is required" 2
fi
[ -e "$input" ] || die "input file not found: $input"
[ -f "$input" ] || die "input path is not a file: $input"
[ -r "$input" ] || die "input file is not readable: $input"

if [ -z "$config" ]; then
  config="$SCRIPT_DIR/config/analyzer.json"
fi
[ -f "$config" ] || die "config file not found: $config"
[ -r "$config" ] || die "config file is not readable: $config"

# jq is optional: when present it gives a fast, clear syntax check before Python runs.
if command -v jq >/dev/null 2>&1; then
  if ! jq empty "$input" >/dev/null 2>&1; then
    die "input file is not valid JSON: $input"
  fi
fi

mkdir -p "$output" || die "cannot create output directory: $output"

export PYTHONPATH="$SCRIPT_DIR${PYTHONPATH:+:$PYTHONPATH}"
exec python3 -m analyzer --config "$config" "$@"
