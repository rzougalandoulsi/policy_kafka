"""One CSV row per recommendation, escaped by the standard library."""

from __future__ import annotations

import csv
from pathlib import Path
from typing import List

from ..models import AnalysisResult, Recommendation

COLUMNS = [
    "recommendation_id",
    "rule_id",
    "classification",
    "status",
    "principal",
    "role",
    "scope",
    "resource_type",
    "existing_binding_ids",
    "proposed_binding",
    "estimated_savings",
    "security_impact",
    "explanation",
]


def _row(rec: Recommendation) -> List[str]:
    return [
        rec.id,
        rec.rule_id,
        rec.classification.value,
        rec.status.value,
        rec.principal,
        rec.role,
        rec.scope_key,
        rec.resource_type,
        ";".join(rec.existing_binding_ids),
        rec.proposed_binding.label if rec.proposed_binding else "",
        str(rec.estimated_savings),
        rec.security_impact,
        rec.explanation if not rec.note else f"{rec.explanation} [{rec.note}]",
    ]


def write_recommendations(result: AnalysisResult, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        writer.writerow(COLUMNS)
        for rec in result.recommendations:
            writer.writerow(_row(rec))
    return path
