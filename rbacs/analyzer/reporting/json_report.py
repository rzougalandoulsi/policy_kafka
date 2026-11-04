"""Machine-readable JSON report. Keys are camelCase to match the input contract."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional

from .. import __version__
from ..models import AnalysisResult, Finding, ProposedBinding, Recommendation, RemediationPlan, RoleBinding, SkippedRecord


def _proposed(proposed: Optional[ProposedBinding]) -> Optional[Dict[str, Any]]:
    if proposed is None:
        return None
    return {
        "principal": proposed.principal,
        "role": proposed.role,
        "scope": proposed.scope_key,
        "resourceType": proposed.resource_type,
        "resourceName": proposed.resource_name,
        "patternType": proposed.pattern_type.value,
    }


def _finding(finding: Finding) -> Dict[str, Any]:
    return {
        "id": finding.id,
        "ruleId": finding.rule_id,
        "classification": finding.classification.value,
        "principal": finding.principal,
        "role": finding.role,
        "scope": finding.scope_key,
        "resourceType": finding.resource_type,
        "reason": finding.reason,
        "evidence": dict(finding.evidence),
        "remediation": {
            "add": [_proposed(p) for p in finding.remediation.add],
            "remove": list(finding.remediation.remove),
            "keep": list(finding.remediation.keep),
        },
        "estimatedSavings": finding.estimated_savings,
        "accessExpansion": finding.access_expansion,
        "warnings": list(finding.warnings),
    }


def _recommendation(rec: Recommendation) -> Dict[str, Any]:
    return {
        "id": rec.id,
        "findingId": rec.finding_id,
        "ruleId": rec.rule_id,
        "status": rec.status.value,
        "classification": rec.classification.value,
        "principal": rec.principal,
        "role": rec.role,
        "scope": rec.scope_key,
        "resourceType": rec.resource_type,
        "existingBindingIds": list(rec.existing_binding_ids),
        "proposedBinding": _proposed(rec.proposed_binding),
        "estimatedSavings": rec.estimated_savings,
        "accessExpansion": rec.access_expansion,
        "securityImpact": rec.security_impact,
        "explanation": rec.explanation,
        "note": rec.note,
    }


def _plan(plan: RemediationPlan) -> Dict[str, Any]:
    return {
        "findingIds": list(plan.finding_ids),
        "add": [_proposed(p) for p in plan.add],
        "remove": list(plan.remove),
        "keep": list(plan.keep),
        "estimatedSavings": plan.estimated_savings,
    }


def _binding(binding: RoleBinding) -> Dict[str, Any]:
    return {
        "id": binding.source_id,
        "principal": binding.principal,
        "role": binding.role,
        "scope": binding.scope.scope_key,
        "resource": binding.resource.label if binding.resource else None,
    }


def _skipped(record: SkippedRecord) -> Dict[str, Any]:
    return {
        "id": record.source_id,
        "index": record.index,
        "status": record.status.value,
        "reasons": list(record.reasons),
        "record": record.raw,
    }


def build_report(result: AnalysisResult) -> Dict[str, Any]:
    summary = result.summary
    return {
        "metadata": {
            "tool": "rbac-analyzer",
            "version": __version__,
            "readOnly": True,
            "input": dict(result.metadata),
            "enabledRules": sorted(result.config.enabled_rules),
            "config": dict(result.config.raw),
        },
        "inputSummary": {
            "totalRecords": summary.total_records,
            "analyzableRecords": summary.analyzable_records,
            "invalidRecords": summary.invalid_records,
            "unsupportedRecords": summary.unsupported_records,
            "uniqueBindings": summary.unique_bindings,
            "principals": summary.principals,
            "literalBindings": summary.literal_bindings,
            "prefixedBindings": summary.prefixed_bindings,
            "noResourceBindings": summary.no_resource_bindings,
            "bindingsPerPrincipal": dict(summary.bindings_per_principal),
            "bindingsPerRole": dict(summary.bindings_per_role),
            "bindingsPerScope": dict(summary.bindings_per_scope),
            "bindingsPerResourceType": dict(summary.bindings_per_resource_type),
            "completeInventory": summary.complete_inventory,
        },
        "findings": [_finding(f) for f in result.findings],
        "recommendations": [_recommendation(r) for r in result.recommendations],
        "safeRemediationPlan": _plan(result.safe_plan),
        "reviewOpportunities": _plan(result.review_plan),
        "statistics": dict(result.statistics),
        "analyzedBindings": [_binding(b) for b in sorted(result.bindings, key=lambda b: b.source_id)],
        "skippedRecords": [_skipped(s) for s in result.skipped],
        "warnings": list(result.warnings),
    }


def write_report(result: AnalysisResult, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(build_report(result), indent=2, sort_keys=True, ensure_ascii=False)
    path.write_text(text + "\n", encoding="utf-8")
    return path
