"""Immutable data model shared by every stage of the analyzer.

Identity rules live here so that validation, rules, and reporting agree on
what makes two role bindings "the same" binding.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, FrozenSet, Iterable, Mapping, Optional, Tuple


class ScopeType(str, Enum):
    """Authorization scopes understood by this release.

    New Confluent scopes (Schema Registry, ksqlDB, Flink) are added here and
    in the validator's required-identifier table; no other code needs to change.
    """

    ORGANIZATION = "ORGANIZATION"
    ENVIRONMENT = "ENVIRONMENT"
    KAFKA_CLUSTER = "KAFKA_CLUSTER"


class PatternType(str, Enum):
    LITERAL = "LITERAL"
    PREFIXED = "PREFIXED"


class Classification(str, Enum):
    SAFE = "SAFE"
    REVIEW = "REVIEW"
    DATA_QUALITY = "DATA_QUALITY"


class Coverage(str, Enum):
    """Three-valued answer to "does binding A make binding B redundant?"."""

    COVERS = "COVERS"
    NOT_COVERED = "NOT_COVERED"
    INDETERMINATE = "INDETERMINATE"


class RecordStatus(str, Enum):
    INVALID = "INVALID"
    UNSUPPORTED = "UNSUPPORTED"


class RecommendationStatus(str, Enum):
    APPROVED_SAFE = "APPROVED_SAFE"
    REVIEW = "REVIEW"
    DEFERRED = "DEFERRED"
    EXCLUDED_BY_POLICY = "EXCLUDED_BY_POLICY"
    CONFLICT = "CONFLICT"
    DATA_QUALITY = "DATA_QUALITY"


RULE_IDS: Tuple[str, ...] = ("RB001", "RB002", "RB003", "RB004", "RB005")

RULE_TITLES: Mapping[str, str] = {
    "RB001": "Exact duplicate bindings",
    "RB002": "LITERAL covered by PREFIXED",
    "RB003": "PREFIXED covered by broader PREFIXED",
    "RB004": "Consolidate LITERAL bindings into a PREFIXED binding",
    "RB005": "Consolidate overlapping PREFIXED bindings",
}


@dataclass(frozen=True)
class Scope:
    type: ScopeType
    organization_id: str
    environment_id: Optional[str] = None
    cluster_id: Optional[str] = None
    crn: Optional[str] = None

    @property
    def scope_key(self) -> str:
        """Full structured scope identity, independent of the CRN string."""
        parts = [self.type.value, self.organization_id]
        if self.environment_id:
            parts.append(self.environment_id)
        if self.cluster_id:
            parts.append(self.cluster_id)
        return "/".join(parts)


@dataclass(frozen=True)
class ResourcePattern:
    type: str
    name: str
    pattern_type: PatternType

    @property
    def label(self) -> str:
        return f"{self.type}:{self.name} ({self.pattern_type.value})"


BindingIdentity = Tuple[str, str, str, str, str, str, str, str, str, str]


@dataclass(frozen=True)
class RoleBinding:
    source_id: str
    principal: str
    role: str
    scope: Scope
    resource: Optional[ResourcePattern]
    raw: Mapping[str, Any] = field(default_factory=dict, compare=False, hash=False, repr=False)

    @property
    def identity(self) -> BindingIdentity:
        """Canonical semantic identity. Deliberately excludes source_id and CRN."""
        resource = self.resource
        return (
            self.principal,
            self.role,
            self.scope.organization_id,
            self.scope.environment_id or "",
            self.scope.cluster_id or "",
            self.scope.type.value,
            self.scope.scope_key,
            resource.type if resource else "",
            resource.name if resource else "",
            resource.pattern_type.value if resource else "",
        )

    @property
    def group_key(self) -> Tuple[str, str, str, str]:
        """Principal, role, exact scope, resource type: the unit every rule works within."""
        return (
            self.principal,
            self.role,
            self.scope.scope_key,
            self.resource.type if self.resource else "",
        )

    @property
    def label(self) -> str:
        resource = self.resource.label if self.resource else "<no resource>"
        return f"{self.principal} | {self.role} | {self.scope.scope_key} | {resource}"


@dataclass(frozen=True)
class SkippedRecord:
    source_id: str
    index: int
    status: RecordStatus
    reasons: Tuple[str, ...]
    raw: Any = field(default=None, compare=False, hash=False, repr=False)


@dataclass(frozen=True)
class ProposedBinding:
    principal: str
    role: str
    scope_key: str
    resource_type: str
    resource_name: str
    pattern_type: PatternType

    @property
    def label(self) -> str:
        return (
            f"{self.principal} | {self.role} | {self.scope_key} | "
            f"{self.resource_type}:{self.resource_name} ({self.pattern_type.value})"
        )


@dataclass(frozen=True)
class Remediation:
    """What a finding proposes. Never executed by this tool."""

    add: Tuple[ProposedBinding, ...] = ()
    remove: Tuple[str, ...] = ()
    keep: Tuple[str, ...] = ()


@dataclass(frozen=True)
class Finding:
    id: str
    rule_id: str
    classification: Classification
    principal: str
    role: str
    scope_key: str
    resource_type: str
    reason: str
    evidence: Mapping[str, Any]
    remediation: Remediation
    estimated_savings: int
    access_expansion: bool
    warnings: Tuple[str, ...] = ()


@dataclass(frozen=True)
class Recommendation:
    """A finding after conflict resolution, with its place in the plan."""

    id: str
    finding_id: str
    rule_id: str
    status: RecommendationStatus
    classification: Classification
    principal: str
    role: str
    scope_key: str
    resource_type: str
    existing_binding_ids: Tuple[str, ...]
    proposed_binding: Optional[ProposedBinding]
    estimated_savings: int
    access_expansion: bool
    security_impact: str
    explanation: str
    note: str = ""


@dataclass(frozen=True)
class RemediationPlan:
    finding_ids: Tuple[str, ...]
    remove: Tuple[str, ...]
    keep: Tuple[str, ...]
    add: Tuple[ProposedBinding, ...]
    estimated_savings: int


@dataclass(frozen=True)
class InputSummary:
    total_records: int
    analyzable_records: int
    invalid_records: int
    unsupported_records: int
    unique_bindings: int
    principals: int
    literal_bindings: int
    prefixed_bindings: int
    no_resource_bindings: int
    bindings_per_principal: Mapping[str, int]
    bindings_per_role: Mapping[str, int]
    bindings_per_scope: Mapping[str, int]
    bindings_per_resource_type: Mapping[str, int]
    complete_inventory: bool


@dataclass(frozen=True)
class Config:
    read_only: bool
    fail_on_ambiguous_scope: bool
    enabled_rules: FrozenSet[str]
    minimum_bindings_to_merge: int
    minimum_prefixes_to_merge: int
    minimum_prefix_length: int
    require_authorized_prefix: bool
    allow_access_expansion: bool
    include_review_candidates: bool
    authorized_prefixes: Tuple[str, ...]
    verified_pattern_semantics: FrozenSet[str]
    raw: Mapping[str, Any] = field(default_factory=dict, compare=False, hash=False, repr=False)

    def with_rules(self, rule_ids: Iterable[str]) -> "Config":
        from dataclasses import replace

        return replace(self, enabled_rules=frozenset(rule_ids))


@dataclass(frozen=True)
class AnalysisResult:
    metadata: Mapping[str, Any]
    summary: InputSummary
    bindings: Tuple[RoleBinding, ...]
    skipped: Tuple[SkippedRecord, ...]
    findings: Tuple[Finding, ...]
    recommendations: Tuple[Recommendation, ...]
    safe_plan: RemediationPlan
    review_plan: RemediationPlan
    statistics: Mapping[str, Any]
    warnings: Tuple[str, ...]
    config: Config


def finding_id(rule_id: str, *parts: str) -> str:
    """Stable identifier derived from rule id and evidence, not from input order."""
    digest = hashlib.sha256()
    digest.update(rule_id.encode("utf-8"))
    for part in sorted(parts):
        digest.update(b"\x1f")
        digest.update(part.encode("utf-8"))
    return f"{rule_id}-{digest.hexdigest()[:12]}"
