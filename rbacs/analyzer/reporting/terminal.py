"""Human-readable terminal report."""

from __future__ import annotations

from typing import List

from ..models import AnalysisResult

BANNER = "================================================="
LABEL_WIDTH = 29
VALUE_WIDTH = 6


def _line(label: str, value: object) -> str:
    text = f"{value:.1f}%" if isinstance(value, float) else str(value)
    return f"{label + ':':<{LABEL_WIDTH}}{text:>{VALUE_WIDTH}}"


def _section(title: str) -> List[str]:
    return ["", title, "-" * len(title)]


def render(result: AnalysisResult, verbose: bool = False) -> str:
    summary = result.summary
    stats = result.statistics
    lines: List[str] = [BANNER, " CONFLUENT CLOUD RBAC OPTIMIZATION ANALYZER", BANNER]

    lines += _section("INPUT SUMMARY")
    lines.append(_line("Total records", summary.total_records))
    lines.append(_line("Unique semantic bindings", summary.unique_bindings))
    lines.append(_line("Principals", summary.principals))
    lines.append(_line("LITERAL bindings", summary.literal_bindings))
    lines.append(_line("PREFIXED bindings", summary.prefixed_bindings))
    lines.append(_line("Bindings without resource", summary.no_resource_bindings))
    lines.append(_line("Invalid records", summary.invalid_records))
    lines.append(_line("Unsupported records", summary.unsupported_records))
    lines.append(_line("Complete inventory", "yes" if summary.complete_inventory else "no"))

    lines += _section("OPTIMIZATION RESULTS")
    lines.append(_line("Duplicate export rows", stats["duplicateExportRows"]))
    lines.append(_line("Removable duplicate bindings", stats["removableDuplicateBindings"]))
    lines.append(_line("Redundant LITERAL bindings", stats["redundantLiteralBindings"]))
    lines.append(_line("Redundant PREFIXED bindings", stats["redundantPrefixedBindings"]))
    lines.append("")
    lines.append(_line("Confirmed safe removals", stats["confirmedSafeRemovals"]))
    lines.append(_line("Potential reduction", float(stats["potentialReductionPercent"])))

    lines += _section("REVIEW OPPORTUNITIES")
    lines.append(_line("Literal consolidations", stats["literalConsolidations"]))
    lines.append(_line("Prefix consolidations", stats["prefixConsolidations"]))
    lines.append(_line("Independent review savings", stats["potentialReviewSavings"]))
    counts = stats["recommendationStatusCounts"]
    lines.append(_line("Excluded by policy", counts["EXCLUDED_BY_POLICY"]))
    lines.append(_line("Deferred (overlapping)", counts["DEFERRED"]))

    lines += _section("SECURITY")
    lines.append(_line("Access-expanding candidates", stats["accessExpandingCandidates"]))
    lines.append(_line("Unsupported bindings", stats["unsupportedBindings"]))
    lines.append(_line("Plan conflicts", counts["CONFLICT"]))

    if verbose:
        lines += _section("FINDINGS")
        if not result.findings:
            lines.append("(none)")
        for finding in result.findings:
            status = next(
                (r.status.value for r in result.recommendations if r.finding_id == finding.id), "OMITTED"
            )
            lines.append(
                f"{finding.id}  {finding.classification.value:<12} {status:<18} "
                f"{finding.principal} | {finding.role} | {finding.scope_key}"
            )
            lines.append(f"    {finding.reason}")
            if finding.remediation.remove:
                lines.append(f"    remove: {', '.join(finding.remediation.remove)}")
            if finding.remediation.keep:
                lines.append(f"    keep:   {', '.join(finding.remediation.keep)}")
            for proposed in finding.remediation.add:
                lines.append(f"    add:    {proposed.label}")

        lines += _section("SKIPPED RECORDS")
        if not result.skipped:
            lines.append("(none)")
        for record in result.skipped:
            lines.append(f"{record.source_id}  {record.status.value:<12} {'; '.join(record.reasons)}")

    if result.warnings:
        lines += _section("WARNINGS")
        shown = result.warnings if verbose else result.warnings[:10]
        lines.extend(f"- {warning}" for warning in shown)
        if len(result.warnings) > len(shown):
            lines.append(f"- ... and {len(result.warnings) - len(shown)} more (use --verbose)")

    lines.append("")
    lines.append("No changes have been applied.")
    return "\n".join(lines) + "\n"
