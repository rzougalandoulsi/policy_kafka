"""Small builders shared by the test modules."""

from __future__ import annotations

import copy
from typing import Any, Dict, List, Optional, Sequence

from analyzer.engine import run_analysis
from analyzer.loader import parse_config
from analyzer.models import AnalysisResult, Config, Finding

BASE_CONFIG: Dict[str, Any] = {
    "authorizedPrefixes": {"team-a": ["team-a.", "team-a.internal."], "team-b": ["team-b."]},
}


def scope(
    kind: str = "KAFKA_CLUSTER",
    org: str = "org-1",
    env: Optional[str] = "env-1",
    cluster: Optional[str] = "lkc-1",
    crn: Optional[str] = None,
) -> Dict[str, Any]:
    result: Dict[str, Any] = {"type": kind, "organizationId": org}
    if kind in ("ENVIRONMENT", "KAFKA_CLUSTER") and env is not None:
        result["environmentId"] = env
    if kind == "KAFKA_CLUSTER" and cluster is not None:
        result["clusterId"] = cluster
    if crn is not None:
        result["crn"] = crn
    return result


def binding(
    id: str,
    name: Optional[str] = None,
    pattern: str = "LITERAL",
    principal: str = "User:sa-1",
    role: str = "DeveloperRead",
    rtype: Optional[str] = "Topic",
    **scope_kwargs: Any,
) -> Dict[str, Any]:
    record: Dict[str, Any] = {
        "id": id,
        "principal": principal,
        "role": role,
        "scope": scope(**scope_kwargs),
    }
    if rtype is not None:
        record["resource"] = {"type": rtype, "name": name, "patternType": pattern}
    return record


def document(bindings: Sequence[Dict[str, Any]], **metadata: Any) -> Dict[str, Any]:
    meta = {"exportedAt": "2026-11-04T12:00:00Z", "organizationId": "org-1", "completeInventory": True}
    meta.update(metadata)
    return {"metadata": meta, "bindings": list(bindings)}


def config(**overrides: Any) -> Config:
    raw = copy.deepcopy(BASE_CONFIG)
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(raw.get(key), dict):
            raw[key].update(value)
        else:
            raw[key] = value
    return parse_config(raw)


def analyze(doc: Dict[str, Any], cfg: Optional[Config] = None) -> AnalysisResult:
    return run_analysis(doc, cfg or config())


def findings_for(result: AnalysisResult, rule_id: str) -> List[Finding]:
    return [f for f in result.findings if f.rule_id == rule_id]
