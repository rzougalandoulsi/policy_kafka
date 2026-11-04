"""Pipeline: validate, summarize, run rules, resolve conflicts, assemble result.

Rules run on one representative per canonical identity, so savings count
semantic bindings and never duplicate export rows. Consolidation runs only on
the bindings that survive SAFE removals, so proposals never overlap findings.
"""

from __future__ import annotations

from typing import Any, Dict, List, Mapping, Optional, Sequence, Set, Tuple

from .models import (
    RULE_IDS,
    AnalysisResult,
    BindingIdentity,
    Classification,
    Config,
    Finding,
    InputSummary,
    PatternType,
    ProposedBinding,
    Recommendation,
    RecommendationStatus,
    RecordStatus,
    RemediationPlan,
    RoleBinding,
    SkippedRecord,
)
from .rules.consolidation import find_consolidations
from .rules.duplicates import find_duplicates
from .rules.prefix_coverage import find_prefix_coverage
from .validator import ValidationOutcome, validate_document


def group_by_identity(bindings: Sequence[RoleBinding]) -> Dict[BindingIdentity, List[RoleBinding]]:
    groups: Dict[BindingIdentity, List[RoleBinding]] = {}
    for binding in bindings:
        groups.setdefault(binding.identity, []).append(binding)
    for members in groups.values():
        members.sort(key=lambda b: b.source_id)
    return groups


def _count(values: Sequence[str]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def summarize_input(
    bindings: Sequence[RoleBinding],
    skipped: Sequence[SkippedRecord],
    representatives: Sequence[RoleBinding],
    complete_inventory: bool,
) -> InputSummary:
    """Baseline statistics. Per-dimension counts use unique semantic bindings."""
    return InputSummary(
        total_records=len(bindings) + len(skipped),
        analyzable_records=len(bindings),
        invalid_records=sum(1 for s in skipped if s.status is RecordStatus.INVALID),
        unsupported_records=sum(1 for s in skipped if s.status is RecordStatus.UNSUPPORTED),
        unique_bindings=len(representatives),
        principals=len({b.principal for b in representatives}),
        literal_bindings=sum(
            1 for b in representatives if b.resource and b.resource.pattern_type is PatternType.LITERAL
        ),
        prefixed_bindings=sum(
            1 for b in representatives if b.resource and b.resource.pattern_type is PatternType.PREFIXED
        ),
        no_resource_bindings=sum(1 for b in representatives if b.resource is None),
        bindings_per_principal=_count([b.principal for b in representatives]),
        bindings_per_role=_count([b.role for b in representatives]),
        bindings_per_scope=_count([b.scope.scope_key for b in representatives]),
        bindings_per_resource_type=_count(
            [b.resource.type if b.resource else "<none>" for b in representatives]
        ),
        complete_inventory=complete_inventory,
    )


def _sort_findings(findings: Sequence[Finding]) -> Tuple[Finding, ...]:
    return tuple(sorted(findings, key=lambda f: (f.rule_id, f.principal, f.id)))


def _security_impact(finding: Finding) -> str:
    if finding.classification is Classification.DATA_QUALITY:
        return "none; export data quality only"
    if finding.access_expansion:
        return "potential access expansion; new prefix may match resources outside the inventory"
    if finding.classification is Classification.REVIEW:
        return "unverified pattern semantics; access unchanged if semantics hold"
    return "none; access unchanged"


def _existing_ids(finding: Finding) -> Tuple[str, ...]:
    if finding.rule_id == "RB001":
        return tuple(finding.evidence.get("duplicateRowIds", ()))
    return finding.remediation.remove


def _recommendation(finding: Finding, status: RecommendationStatus, note: str = "") -> Recommendation:
    explanation = finding.reason
    if finding.warnings:
        explanation += " " + " ".join(finding.warnings)
    return Recommendation(
        id=finding.id,
        finding_id=finding.id,
        rule_id=finding.rule_id,
        status=status,
        classification=finding.classification,
        principal=finding.principal,
        role=finding.role,
        scope_key=finding.scope_key,
        resource_type=finding.resource_type,
        existing_binding_ids=_existing_ids(finding),
        proposed_binding=finding.remediation.add[0] if finding.remediation.add else None,
        estimated_savings=finding.estimated_savings,
        access_expansion=finding.access_expansion,
        security_impact=_security_impact(finding),
        explanation=explanation,
        note=note,
    )


def resolve_plan(
    findings: Sequence[Finding], config: Config
) -> Tuple[RemediationPlan, RemediationPlan, Tuple[Recommendation, ...], List[str]]:
    """Turn findings into one coherent plan.

    SAFE findings are admitted in a fixed order (coverage first, then duplicates)
    and every binding id is removed at most once. REVIEW findings are admitted
    greedily by savings, and any that touch an already-claimed binding or
    propose an already-proposed binding are deferred rather than double counted.
    """
    warnings: List[str] = []
    statuses: Dict[str, Tuple[RecommendationStatus, str]] = {}

    safe_order = {"RB002": 0, "RB003": 1, "RB001": 2}
    safe_findings = sorted(
        (f for f in findings if f.classification is Classification.SAFE),
        key=lambda f: (safe_order.get(f.rule_id, 9), f.principal, f.id),
    )
    safe_remove: Set[str] = set()
    safe_keep: Set[str] = set()
    safe_ids: List[str] = []
    for finding in safe_findings:
        remove = set(finding.remediation.remove)
        keep = set(finding.remediation.keep)
        if finding.rule_id == "RB001" and keep & safe_remove:
            # The retained duplicate is itself removed by a coverage finding, so
            # every row of the duplicate group goes; nothing is double counted.
            remove |= keep
            keep = set()
            note = "retained binding is removed by a coverage finding; all duplicate rows are removed"
        elif remove & safe_keep or keep & safe_remove:
            statuses[finding.id] = (
                RecommendationStatus.CONFLICT,
                "conflicts with an earlier approved finding on the same binding ids",
            )
            warnings.append(f"finding {finding.id} conflicts with an earlier approved finding; left out of the plan")
            continue
        else:
            note = ""
        safe_remove |= remove
        safe_keep |= keep
        safe_ids.append(finding.id)
        statuses[finding.id] = (RecommendationStatus.APPROVED_SAFE, note)

    safe_plan = RemediationPlan(
        finding_ids=tuple(safe_ids),
        remove=tuple(sorted(safe_remove)),
        keep=tuple(sorted(safe_keep - safe_remove)),
        add=(),
        estimated_savings=len(safe_remove),
    )

    touched: Set[str] = safe_remove | safe_keep
    proposed_labels: Set[str] = set()
    review_ids: List[str] = []
    review_remove: Set[str] = set()
    review_keep: Set[str] = set()
    review_add: List[ProposedBinding] = []
    review_findings = sorted(
        (f for f in findings if f.classification is Classification.REVIEW),
        key=lambda f: (-f.estimated_savings, f.rule_id, f.id),
    )
    for finding in review_findings:
        ids = set(finding.remediation.remove) | set(finding.remediation.keep)
        labels = {p.label for p in finding.remediation.add}
        if finding.access_expansion and not config.allow_access_expansion:
            statuses[finding.id] = (
                RecommendationStatus.EXCLUDED_BY_POLICY,
                "consolidation.allowAccessExpansion is false; listed for manual review only",
            )
            continue
        if ids & touched:
            statuses[finding.id] = (
                RecommendationStatus.DEFERRED,
                "touches a binding already claimed by a higher-ranked recommendation",
            )
            continue
        if labels & proposed_labels:
            statuses[finding.id] = (
                RecommendationStatus.DEFERRED,
                "proposes a binding already proposed by a higher-ranked recommendation",
            )
            continue
        touched |= ids
        proposed_labels |= labels
        review_ids.append(finding.id)
        review_remove |= set(finding.remediation.remove)
        review_keep |= set(finding.remediation.keep)
        review_add.extend(finding.remediation.add)
        statuses[finding.id] = (RecommendationStatus.REVIEW, "")

    review_plan = RemediationPlan(
        finding_ids=tuple(sorted(review_ids)),
        remove=tuple(sorted(review_remove)),
        keep=tuple(sorted(review_keep)),
        add=tuple(sorted(review_add, key=lambda p: p.label)),
        estimated_savings=len(review_remove) - len(review_add),
    )

    recommendations: List[Recommendation] = []
    for finding in findings:
        if finding.classification is Classification.DATA_QUALITY:
            recommendations.append(_recommendation(finding, RecommendationStatus.DATA_QUALITY))
            continue
        status, note = statuses[finding.id]
        if status is RecommendationStatus.EXCLUDED_BY_POLICY and not config.include_review_candidates:
            continue
        recommendations.append(_recommendation(finding, status, note))

    return safe_plan, review_plan, tuple(recommendations), warnings


def _statistics(
    summary: InputSummary,
    findings: Sequence[Finding],
    recommendations: Sequence[Recommendation],
    safe_plan: RemediationPlan,
    review_plan: RemediationPlan,
) -> Dict[str, Any]:
    approved = {r.finding_id for r in recommendations if r.status is RecommendationStatus.APPROVED_SAFE}
    by_rule = {rule_id: 0 for rule_id in RULE_IDS}
    for finding in findings:
        by_rule[finding.rule_id] += 1

    def removed_by(rule_id: str) -> int:
        ids: Set[str] = set()
        for finding in findings:
            if finding.rule_id == rule_id and finding.id in approved:
                ids.update(finding.remediation.remove)
        return len(ids & set(safe_plan.remove))

    duplicate_rows = sum(
        int(f.evidence.get("rowCount", 1)) - 1 for f in findings if f.rule_id == "RB001"
    )
    confirmed = safe_plan.estimated_savings
    reduction = (confirmed / summary.unique_bindings * 100.0) if summary.unique_bindings else 0.0
    status_counts = {status.value: 0 for status in RecommendationStatus}
    for rec in recommendations:
        status_counts[rec.status.value] += 1
    return {
        "findingsByRule": by_rule,
        "duplicateExportRows": duplicate_rows,
        "redundantLiteralBindings": removed_by("RB002"),
        "redundantPrefixedBindings": removed_by("RB003"),
        "removableDuplicateBindings": removed_by("RB001"),
        "confirmedSafeRemovals": confirmed,
        "potentialReductionPercent": round(reduction, 1),
        "literalConsolidations": by_rule["RB004"],
        "prefixConsolidations": by_rule["RB005"],
        "accessExpandingCandidates": sum(1 for f in findings if f.access_expansion),
        "unsupportedBindings": summary.unsupported_records,
        "invalidRecords": summary.invalid_records,
        "estimatedSafeSavings": confirmed,
        "potentialReviewSavings": review_plan.estimated_savings,
        "recommendationStatusCounts": status_counts,
    }


def run_analysis(document: Any, config: Config) -> AnalysisResult:
    outcome: ValidationOutcome = validate_document(document, config)
    metadata = outcome.metadata
    groups = group_by_identity(outcome.bindings)
    representatives = tuple(sorted((members[0] for members in groups.values()), key=lambda b: b.source_id))
    duplicate_rows = {
        members[0].source_id: tuple(m.source_id for m in members[1:])
        for members in groups.values()
        if len(members) > 1
    }
    summary = summarize_input(outcome.bindings, outcome.skipped, representatives, metadata["completeInventory"])

    findings: List[Finding] = []
    enabled = config.enabled_rules
    if "RB001" in enabled:
        findings.extend(find_duplicates(groups, metadata["bindingIdsAreConfluentBindingIds"]))
    if enabled & {"RB002", "RB003"}:
        findings.extend(find_prefix_coverage(representatives, duplicate_rows, config))

    safe_removed: Set[str] = set()
    for finding in findings:
        if finding.classification is Classification.SAFE:
            safe_removed.update(finding.remediation.remove)
    survivors = [b for b in representatives if b.source_id not in safe_removed]
    if enabled & {"RB004", "RB005"}:
        findings.extend(
            find_consolidations(survivors, outcome.bindings, config, metadata["completeInventory"])
        )

    ordered = _sort_findings(findings)
    safe_plan, review_plan, recommendations, plan_warnings = resolve_plan(ordered, config)
    statistics = _statistics(summary, ordered, recommendations, safe_plan, review_plan)

    return AnalysisResult(
        metadata=metadata,
        summary=summary,
        bindings=outcome.bindings,
        skipped=outcome.skipped,
        findings=ordered,
        recommendations=recommendations,
        safe_plan=safe_plan,
        review_plan=review_plan,
        statistics=statistics,
        warnings=tuple(outcome.warnings) + tuple(plan_warnings),
        config=config,
    )
