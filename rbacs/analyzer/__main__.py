"""Command-line entry point: python3 -m analyzer --input FILE [options]."""

from __future__ import annotations

import argparse
import sys
import traceback
from pathlib import Path
from typing import List, Optional, Sequence

from . import __version__
from .engine import run_analysis
from .errors import AnalyzerError, UsageError
from .loader import load_config, load_inventory
from .models import RULE_IDS
from .reporting import csv_report, json_report, terminal

FORMATS = ("terminal", "json", "csv", "all")
EXIT_UNEXPECTED = 3


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="analyze.sh",
        description="Read-only analyzer for Confluent Cloud RBAC role-binding inventories.",
        epilog="The analyzer never modifies Confluent Cloud. It only writes reports to --output.",
    )
    parser.add_argument("--input", required=True, metavar="FILE", help="exported RBAC inventory (JSON)")
    parser.add_argument("--config", metavar="FILE", help="analyzer configuration (default: config/analyzer.json)")
    parser.add_argument("--output", default="./output", metavar="DIR", help="report directory (default: ./output)")
    parser.add_argument(
        "--rules",
        metavar="IDS",
        help="comma-separated rule ids to run, e.g. RB001,RB002 (default: rules enabled in config)",
    )
    parser.add_argument(
        "--format",
        action="append",
        choices=FORMATS,
        metavar="FMT",
        help="terminal, json, csv, or all; repeatable (default: all)",
    )
    parser.add_argument("--verbose", action="store_true", help="list findings, skipped records, and all warnings")
    parser.add_argument("--version", action="version", version=f"rbac-analyzer {__version__}")
    return parser


def parse_rules(value: Optional[str], default: Sequence[str]) -> List[str]:
    if value is None:
        return sorted(default)
    requested = [item.strip() for item in value.split(",") if item.strip()]
    if not requested:
        raise UsageError("--rules must list at least one rule id")
    unknown = sorted(set(requested) - set(RULE_IDS))
    if unknown:
        raise UsageError(f"unknown rule id(s): {', '.join(unknown)}; known: {', '.join(RULE_IDS)}")
    return sorted(set(requested))


def resolve_formats(values: Optional[Sequence[str]]) -> List[str]:
    chosen = set(values or ["all"])
    if "all" in chosen:
        return ["terminal", "json", "csv"]
    return [fmt for fmt in ("terminal", "json", "csv") if fmt in chosen]


def run(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        config = load_config(Path(args.config) if args.config else None)
        config = config.with_rules(parse_rules(args.rules, config.enabled_rules))
        document = load_inventory(Path(args.input))
        result = run_analysis(document, config)

        output_dir = Path(args.output)
        formats = resolve_formats(args.format)
        if "terminal" in formats:
            sys.stdout.write(terminal.render(result, verbose=args.verbose))
        if "json" in formats:
            path = json_report.write_report(result, output_dir / "report.json")
            sys.stdout.write(f"Wrote {path}\n")
        if "csv" in formats:
            path = csv_report.write_recommendations(result, output_dir / "recommendations.csv")
            sys.stdout.write(f"Wrote {path}\n")
        return 0
    except AnalyzerError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return exc.exit_code
    except Exception as exc:  # pragma: no cover - defensive; ordinary failures are AnalyzerError
        sys.stderr.write(f"unexpected error: {exc.__class__.__name__}: {exc}\n")
        if args.verbose:
            traceback.print_exc()
        return EXIT_UNEXPECTED


def main() -> None:
    sys.exit(run())


if __name__ == "__main__":
    main()
