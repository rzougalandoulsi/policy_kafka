"""Pure coverage semantics. Conservative by design.

A binding covers another only when principal, role, scope type, and the full
structured scope are identical and the resource pattern strictly contains the
target. Role inheritance and cross-scope inheritance are deliberately absent;
a later role-permission matrix (Phase 4) plugs into binding_covers.
"""

from __future__ import annotations

from typing import Collection, Dict, Iterable, List, Tuple

from .models import Coverage, PatternType, ResourcePattern, RoleBinding


def pattern_covers(covering_pattern: ResourcePattern, target_pattern: ResourcePattern) -> bool:
    """String semantics only: PREFIXED p covers LITERAL n when n starts with p,
    and covers PREFIXED q when q is strictly longer and starts with p."""
    if covering_pattern.type != target_pattern.type:
        return False
    if covering_pattern.pattern_type is not PatternType.PREFIXED:
        return False
    prefix = covering_pattern.name
    if prefix == "":
        return False
    name = target_pattern.name
    if target_pattern.pattern_type is PatternType.LITERAL:
        return name.startswith(prefix)
    if target_pattern.pattern_type is PatternType.PREFIXED:
        return len(name) > len(prefix) and name.startswith(prefix)
    return False


def binding_covers(
    covering: RoleBinding,
    target: RoleBinding,
    verified_resource_types: Collection[str] = (),
) -> Coverage:
    """Whether `covering` makes `target` redundant.

    COVERS only when every identity dimension except the resource pattern
    matches and the resource type's PREFIXED semantics are verified.
    INDETERMINATE when the pattern matches but the resource type is unverified.
    """
    if covering.source_id == target.source_id:
        return Coverage.NOT_COVERED
    if covering.principal != target.principal:
        return Coverage.NOT_COVERED
    if covering.role != target.role:
        return Coverage.NOT_COVERED
    if covering.scope.type is not target.scope.type:
        return Coverage.NOT_COVERED
    if covering.scope.scope_key != target.scope.scope_key:
        return Coverage.NOT_COVERED
    if covering.resource is None or target.resource is None:
        return Coverage.NOT_COVERED
    if not pattern_covers(covering.resource, target.resource):
        return Coverage.NOT_COVERED
    if covering.resource.type not in verified_resource_types:
        return Coverage.INDETERMINATE
    return Coverage.COVERS


def group_bindings(bindings: Iterable[RoleBinding]) -> Dict[Tuple[str, str, str, str], List[RoleBinding]]:
    """Group by principal, role, exact scope, resource type, each group sorted by source id."""
    groups: Dict[Tuple[str, str, str, str], List[RoleBinding]] = {}
    for binding in bindings:
        groups.setdefault(binding.group_key, []).append(binding)
    for members in groups.values():
        members.sort(key=lambda b: b.source_id)
    return groups
