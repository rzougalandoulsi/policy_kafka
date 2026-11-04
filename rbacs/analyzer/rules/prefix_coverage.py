"""RB002 and RB003: bindings made redundant by an existing PREFIXED binding."""

from __future__ import annotations

from typing import Dict, List, Mapping, Sequence, Tuple

from ..coverage import binding_covers, group_bindings
from ..models import (
    Classification,
    Config,
    Coverage,
    Finding,
    PatternType,
    Remediation,
    RoleBinding,
    finding_id,
)

RULE_LITERAL = "RB002"
RULE_PREFIXED = "RB003"

_REASONS = {
    RULE_LITERAL: "LITERAL resource is covered by an existing PREFIXED binding",
    RULE_PREFIXED: "PREFIXED resource is covered by a broader existing PREFIXED binding",
}


def choose_covering(candidates: Sequence[Tuple[RoleBinding, Coverage]]) -> Tuple[RoleBinding, Coverage]:
    """Deterministic choice: broadest prefix first, then lowest source id.

    The broadest cover is never itself covered by another candidate, so the
    binding kept here never appears in a removal set of the same group.
    """
    return sorted(candidates, key=lambda pair: (len(pair[0].resource.name), pair[0].source_id))[0]


def find_prefix_coverage(
    representatives: Sequence[RoleBinding],
    duplicate_rows: Mapping[str, Tuple[str, ...]],
    config: Config,
) -> List[Finding]:
    """Run RB002 and/or RB003 according to config.enabled_rules.

    `representatives` holds one binding per canonical identity; `duplicate_rows`
    maps a representative id to the ids of its duplicate export rows, kept as
    evidence only.
    """
    enabled = config.enabled_rules
    verified = config.verified_pattern_semantics
    findings: List[Finding] = []

    for members in group_bindings(representatives).values():
        with_resource = [b for b in members if b.resource is not None]
        prefixed = [b for b in with_resource if b.resource.pattern_type is PatternType.PREFIXED]
        if not prefixed:
            continue

        buckets: Dict[Tuple[str, str], List[Tuple[RoleBinding, List[str], Coverage]]] = {}
        for target in with_resource:
            rule_id = RULE_LITERAL if target.resource.pattern_type is PatternType.LITERAL else RULE_PREFIXED
            if rule_id not in enabled:
                continue
            candidates = []
            for cover in prefixed:
                result = binding_covers(cover, target, verified)
                if result is not Coverage.NOT_COVERED:
                    candidates.append((cover, result))
            if not candidates:
                continue
            chosen, result = choose_covering(candidates)
            alternatives = sorted(c.source_id for c, _ in candidates if c.source_id != chosen.source_id)
            buckets.setdefault((chosen.source_id, rule_id), []).append((target, alternatives, result))

        for (cover_id, rule_id), entries in sorted(buckets.items()):
            cover = next(b for b in prefixed if b.source_id == cover_id)
            entries.sort(key=lambda entry: entry[0].source_id)
            redundant_ids = tuple(target.source_id for target, _, _ in entries)
            determinate = all(result is Coverage.COVERS for _, _, result in entries)
            classification = Classification.SAFE if determinate else Classification.REVIEW
            warnings: Tuple[str, ...] = ()
            reason = _REASONS[rule_id]
            if not determinate:
                reason += (
                    f"; PREFIXED semantics for resource type {cover.resource.type!r} are not verified, "
                    "so removal needs manual review"
                )
                warnings = (
                    f"Add {cover.resource.type!r} to verifiedPatternSemantics once its PREFIXED semantics "
                    "are confirmed to classify this finding as SAFE.",
                )
            evidence = {
                "coveringBindingId": cover.source_id,
                "coveringBinding": cover.label,
                "coveringPrefix": cover.resource.name,
                "redundantBindingIds": list(redundant_ids),
                "redundantBindings": [
                    {
                        "id": target.source_id,
                        "resource": target.resource.label,
                        "alternativeCoveringBindingIds": alternatives,
                        "duplicateRowIds": list(duplicate_rows.get(target.source_id, ())),
                    }
                    for target, alternatives, _ in entries
                ],
                "coverage": "COVERS" if determinate else "INDETERMINATE",
            }
            findings.append(
                Finding(
                    id=finding_id(rule_id, cover.source_id, *redundant_ids),
                    rule_id=rule_id,
                    classification=classification,
                    principal=cover.principal,
                    role=cover.role,
                    scope_key=cover.scope.scope_key,
                    resource_type=cover.resource.type,
                    reason=reason,
                    evidence=evidence,
                    remediation=Remediation(remove=redundant_ids, keep=(cover.source_id,)),
                    estimated_savings=len(redundant_ids),
                    access_expansion=False,
                    warnings=warnings,
                )
            )
    return findings
