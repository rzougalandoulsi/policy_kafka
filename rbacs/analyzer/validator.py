"""Turn a raw inventory document into canonical bindings plus skipped records.

Three outcomes per record:
  * INVALID      - the record cannot be trusted (missing fields, bad types,
                   duplicate id). Preserved in the skipped list.
  * UNSUPPORTED  - the record is well formed but this release cannot reason
                   about it safely (unknown scope type, unknown pattern type,
                   empty prefix, ambiguous scope). Preserved in the skipped list.
  * analyzable   - becomes a RoleBinding.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Mapping, Optional, Set, Tuple

from .errors import InputError
from .models import (
    Config,
    PatternType,
    RecordStatus,
    ResourcePattern,
    RoleBinding,
    Scope,
    ScopeType,
    SkippedRecord,
)

# Required and forbidden identifiers per scope type. A forbidden identifier that
# is present makes the scope ambiguous (is it a cluster binding or not?).
SCOPE_REQUIREMENTS: Mapping[ScopeType, Tuple[Tuple[str, ...], Tuple[str, ...]]] = {
    ScopeType.ORGANIZATION: (("organizationId",), ("environmentId", "clusterId")),
    ScopeType.ENVIRONMENT: (("organizationId", "environmentId"), ("clusterId",)),
    ScopeType.KAFKA_CLUSTER: (("organizationId", "environmentId", "clusterId"), ()),
}


@dataclass(frozen=True)
class ValidationOutcome:
    metadata: Mapping[str, Any]
    bindings: Tuple[RoleBinding, ...]
    skipped: Tuple[SkippedRecord, ...]
    warnings: Tuple[str, ...]


@dataclass
class _RecordCheck:
    invalid: List[str]
    unsupported: List[str]
    ambiguous: List[str]

    def status(self) -> Optional[RecordStatus]:
        if self.invalid:
            return RecordStatus.INVALID
        if self.unsupported or self.ambiguous:
            return RecordStatus.UNSUPPORTED
        return None

    def reasons(self) -> Tuple[str, ...]:
        return tuple(self.invalid + self.unsupported + self.ambiguous)


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and value.strip() != ""


def _optional_string(value: Any) -> bool:
    return value is None or isinstance(value, str)


def validate_metadata(doc: Any) -> Tuple[Dict[str, Any], List[str]]:
    if not isinstance(doc, dict):
        raise InputError("input document must be a JSON object with 'metadata' and 'bindings'")
    metadata = doc.get("metadata")
    if not isinstance(metadata, dict):
        raise InputError("input document is missing the required 'metadata' object")
    if not _nonempty_string(metadata.get("organizationId")):
        raise InputError("metadata.organizationId is required and must be a non-empty string")
    if "bindings" not in doc:
        raise InputError("input document is missing the required 'bindings' array")
    if not isinstance(doc["bindings"], list):
        raise InputError("'bindings' must be a JSON array")

    warnings: List[str] = []
    normalized: Dict[str, Any] = dict(metadata)
    if not _nonempty_string(metadata.get("exportedAt")):
        warnings.append("metadata.exportedAt is missing; the report cannot state when the inventory was taken")
        normalized["exportedAt"] = None
    complete = metadata.get("completeInventory")
    if not isinstance(complete, bool):
        warnings.append(
            "metadata.completeInventory is missing or not boolean; treating the inventory as partial"
        )
        normalized["completeInventory"] = False
    ids_flag = metadata.get("bindingIdsAreConfluentBindingIds")
    if ids_flag is not None and not isinstance(ids_flag, bool):
        raise InputError("metadata.bindingIdsAreConfluentBindingIds must be true or false when present")
    normalized["bindingIdsAreConfluentBindingIds"] = bool(ids_flag)
    return normalized, warnings


def _parse_scope(raw: Any, org_id: str, check: _RecordCheck) -> Optional[Scope]:
    if not isinstance(raw, dict):
        check.invalid.append("scope must be an object")
        return None
    scope_type_raw = raw.get("type")
    if not _nonempty_string(scope_type_raw):
        check.invalid.append("scope.type is required")
        return None
    try:
        scope_type = ScopeType(scope_type_raw)
    except ValueError:
        check.unsupported.append(f"scope.type {scope_type_raw!r} is not supported by this release")
        return None

    for key in ("organizationId", "environmentId", "clusterId", "crn"):
        if not _optional_string(raw.get(key)):
            check.invalid.append(f"scope.{key} must be a string when present")
    if check.invalid:
        return None

    required, forbidden = SCOPE_REQUIREMENTS[scope_type]
    for key in required:
        if not _nonempty_string(raw.get(key)):
            check.invalid.append(f"scope.{key} is required for scope type {scope_type.value}")
    for key in forbidden:
        if _nonempty_string(raw.get(key)):
            check.ambiguous.append(
                f"scope.{key} is present on a {scope_type.value} scope; the scope is ambiguous"
            )
    if check.invalid or check.ambiguous:
        return None

    if raw["organizationId"] != org_id:
        check.ambiguous.append(
            f"scope.organizationId {raw['organizationId']!r} differs from metadata.organizationId {org_id!r}"
        )
        return None

    return Scope(
        type=scope_type,
        organization_id=raw["organizationId"],
        environment_id=raw.get("environmentId") or None,
        cluster_id=raw.get("clusterId") or None,
        crn=raw.get("crn") or None,
    )


def _parse_resource(raw: Any, check: _RecordCheck) -> Optional[ResourcePattern]:
    if raw is None:
        return None
    if not isinstance(raw, dict):
        check.invalid.append("resource must be an object when present")
        return None
    resource_type = raw.get("type")
    name = raw.get("name")
    pattern_raw = raw.get("patternType")
    if not _nonempty_string(resource_type):
        check.invalid.append("resource.type is required")
    if not isinstance(name, str):
        check.invalid.append("resource.name must be a string")
    if not _nonempty_string(pattern_raw):
        check.invalid.append("resource.patternType is required")
    if check.invalid:
        return None
    try:
        pattern_type = PatternType(pattern_raw)
    except ValueError:
        check.unsupported.append(f"resource.patternType {pattern_raw!r} is not supported by this release")
        return None
    if pattern_type is PatternType.LITERAL and name == "":
        check.invalid.append("resource.name must not be empty for a LITERAL pattern")
        return None
    if pattern_type is PatternType.PREFIXED and name == "":
        check.unsupported.append("an empty PREFIXED resource name would match every resource; not analyzed")
        return None
    return ResourcePattern(type=resource_type, name=name, pattern_type=pattern_type)


def _parse_record(
    index: int, raw: Any, org_id: str
) -> Tuple[str, _RecordCheck, Optional[RoleBinding], List[str]]:
    warnings: List[str] = []
    check = _RecordCheck([], [], [])
    if not isinstance(raw, dict):
        check.invalid.append("record is not a JSON object")
        return f"row-{index}", check, None, warnings

    raw_id = raw.get("id")
    if raw_id is None or raw_id == "":
        source_id = f"row-{index}"
        warnings.append(f"record at index {index} has no id; synthesized {source_id}")
    elif isinstance(raw_id, str):
        source_id = raw_id
    else:
        source_id = f"row-{index}"
        check.invalid.append("id must be a string")

    if not _nonempty_string(raw.get("principal")):
        check.invalid.append("principal is required and must be non-empty")
    if not _nonempty_string(raw.get("role")):
        check.invalid.append("role is required and must be non-empty")

    scope = _parse_scope(raw.get("scope"), org_id, check)
    resource = _parse_resource(raw.get("resource"), check)

    if check.status() is not None or scope is None:
        return source_id, check, None, warnings

    binding = RoleBinding(
        source_id=source_id,
        principal=raw["principal"],
        role=raw["role"],
        scope=scope,
        resource=resource,
        raw=raw,
    )
    return source_id, check, binding, warnings


def validate_document(doc: Any, config: Config) -> ValidationOutcome:
    metadata, warnings = validate_metadata(doc)
    org_id = metadata["organizationId"]
    records = doc["bindings"]

    parsed: List[Tuple[int, str, _RecordCheck, Optional[RoleBinding], Any]] = []
    for index, raw in enumerate(records):
        source_id, check, binding, record_warnings = _parse_record(index, raw, org_id)
        warnings.extend(record_warnings)
        parsed.append((index, source_id, check, binding, raw))

    # Duplicate source ids: a removal proposal must name exactly one record.
    id_counts: Dict[str, int] = {}
    for _, source_id, _, _, _ in parsed:
        id_counts[source_id] = id_counts.get(source_id, 0) + 1
    for _, source_id, check, _, _ in parsed:
        if id_counts[source_id] > 1:
            check.invalid.append(f"id {source_id!r} is shared by {id_counts[source_id]} records")

    # Conflicting CRNs for one structured scope make that scope ambiguous.
    crns_by_scope: Dict[str, Set[str]] = {}
    for _, _, check, binding, _ in parsed:
        if binding is not None and check.status() is None and binding.scope.crn:
            crns_by_scope.setdefault(binding.scope.scope_key, set()).add(binding.scope.crn)
    for _, _, check, binding, _ in parsed:
        if binding is None or check.status() is not None:
            continue
        crns = crns_by_scope.get(binding.scope.scope_key, set())
        if len(crns) > 1:
            check.ambiguous.append(
                f"scope {binding.scope.scope_key} appears with {len(crns)} different CRNs"
            )

    ambiguous_ids = sorted(source_id for _, source_id, check, _, _ in parsed if check.ambiguous)
    if ambiguous_ids and config.fail_on_ambiguous_scope:
        shown = ", ".join(ambiguous_ids[:10])
        more = "" if len(ambiguous_ids) <= 10 else f" (and {len(ambiguous_ids) - 10} more)"
        raise InputError(
            f"{len(ambiguous_ids)} record(s) have an ambiguous scope: {shown}{more}. "
            "Set analysis.failOnAmbiguousScope to false to skip them instead."
        )

    bindings: List[RoleBinding] = []
    skipped: List[SkippedRecord] = []
    for index, source_id, check, binding, raw in parsed:
        status = check.status()
        if status is None and binding is not None:
            bindings.append(binding)
        else:
            skipped.append(
                SkippedRecord(
                    source_id=source_id,
                    index=index,
                    status=status or RecordStatus.INVALID,
                    reasons=check.reasons(),
                    raw=raw,
                )
            )

    if not metadata["completeInventory"]:
        warnings.append(
            "inventory is marked as partial; consolidation candidates may miss bindings that exist in Confluent Cloud"
        )

    return ValidationOutcome(
        metadata=metadata,
        bindings=tuple(bindings),
        skipped=tuple(skipped),
        warnings=tuple(warnings),
    )
