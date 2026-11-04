"""RB004 and RB005: replace several bindings with one PREFIXED binding.

Every candidate is REVIEW. A new prefix may authorize resources that are not
in the inventory, so the inventory alone can never prove the candidate safe.
"""

from __future__ import annotations

from typing import Dict, List, Sequence, Set, Tuple

from ..coverage import group_bindings, pattern_covers
from ..models import (
    Classification,
    Config,
    Finding,
    PatternType,
    ProposedBinding,
    Remediation,
    RoleBinding,
    finding_id,
)

RULE_LITERALS = "RB004"
RULE_PREFIXES = "RB005"

PREFIX_DELIMITERS = ".-_/:"


def boundary_prefixes(name: str, minimum_length: int) -> List[str]:
    """Prefixes of `name` that end on a delimiter, never shorter than the minimum
    and never the whole name. 'team-a.orders.v2' -> ['team-', 'team-a.', 'team-a.orders.']."""
    result = []
    for index, char in enumerate(name):
        length = index + 1
        if char in PREFIX_DELIMITERS and minimum_length <= length < len(name):
            result.append(name[:length])
    return result


def candidate_prefixes(names: Sequence[str], config: Config) -> List[str]:
    """Authorized prefixes, plus delimiter-bounded common prefixes when allowed."""
    candidates: Set[str] = set(config.authorized_prefixes)
    if not config.require_authorized_prefix:
        for name in names:
            candidates.update(boundary_prefixes(name, config.minimum_prefix_length))
    return sorted(c for c in candidates if c)


def _literal_matches(prefix: str, name: str) -> bool:
    return name.startswith(prefix)


def _prefix_matches(prefix: str, name: str) -> bool:
    return len(name) > len(prefix) and name.startswith(prefix)


def assign_to_prefixes(
    names: Sequence[str],
    candidates: Sequence[str],
    minimum: int,
    matches,
) -> Dict[str, List[str]]:
    """Give each name to the longest candidate that still meets the threshold.

    Candidates that fall below the threshold after assignment are dropped and
    their names flow to the next shorter candidate, until stable. The candidate
    set only shrinks, so this terminates.
    """
    active = [c for c in candidates if sum(1 for n in names if matches(c, n)) >= minimum]
    while True:
        assignment: Dict[str, List[str]] = {c: [] for c in active}
        for name in sorted(set(names)):
            matching = [c for c in active if matches(c, name)]
            if matching:
                assignment[max(matching, key=len)].append(name)
        dropped = [c for c in active if len(assignment[c]) < minimum]
        if not dropped:
            return {c: members for c, members in sorted(assignment.items()) if members}
        active = [c for c in active if c not in dropped]


def _covered_by_existing(candidate: str, existing_prefixes: Sequence[str]) -> bool:
    return any(candidate.startswith(p) for p in existing_prefixes)


def _known_expanded_resources(
    inventory: Sequence[RoleBinding],
    group: Sequence[RoleBinding],
    resource_type: str,
    candidate: str,
) -> List[str]:
    """LITERAL names anywhere in the inventory that the candidate would newly
    match and that the principal does not already hold through this group."""
    held = [b.resource for b in group if b.resource is not None]
    names: Set[str] = set()
    for binding in inventory:
        resource = binding.resource
        if resource is None or resource.type != resource_type:
            continue
        if resource.pattern_type is not PatternType.LITERAL or not resource.name.startswith(candidate):
            continue
        if any(pattern_covers(h, resource) or h == resource for h in held):
            continue
        names.add(resource.name)
    return sorted(names)


def _build_finding(
    rule_id: str,
    group: Sequence[RoleBinding],
    candidate: str,
    members: Sequence[RoleBinding],
    inventory: Sequence[RoleBinding],
    config: Config,
    inventory_complete: bool,
) -> Finding:
    sample = members[0]
    resource_type = sample.resource.type
    member_ids = tuple(sorted(b.source_id for b in members))
    member_names = [b.resource.name for b in members]
    proposed = ProposedBinding(
        principal=sample.principal,
        role=sample.role,
        scope_key=sample.scope.scope_key,
        resource_type=resource_type,
        resource_name=candidate,
        pattern_type=PatternType.PREFIXED,
    )
    expanded = _known_expanded_resources(inventory, group, resource_type, candidate)
    authorized = candidate in config.authorized_prefixes
    kind = "LITERAL" if rule_id == RULE_LITERALS else "PREFIXED"
    reason = (
        f"{len(members)} {kind} bindings fall under the "
        f"{'authorized' if authorized else 'derived'} prefix {candidate!r} and could be replaced by "
        f"one PREFIXED binding, saving {len(members) - 1}"
    )
    warnings = [
        f"The proposed PREFIXED binding would also authorize any current or future {resource_type} whose "
        f"name starts with {candidate!r}; the RBAC inventory alone cannot prove the full set of resources."
    ]
    if expanded:
        warnings.append(
            f"{len(expanded)} {resource_type} name(s) already present in the inventory under other bindings "
            "would newly match this prefix."
        )
    if not inventory_complete:
        warnings.append("The inventory is marked partial; more bindings may exist under this prefix.")
    if not authorized:
        warnings.append("This prefix is not in authorizedPrefixes; confirm it is an approved boundary.")
    return Finding(
        id=finding_id(rule_id, candidate, *member_ids),
        rule_id=rule_id,
        classification=Classification.REVIEW,
        principal=sample.principal,
        role=sample.role,
        scope_key=sample.scope.scope_key,
        resource_type=resource_type,
        reason=reason,
        evidence={
            "candidatePrefix": candidate,
            "authorizedPrefix": authorized,
            "existingBindingIds": list(member_ids),
            "existingResources": sorted(member_names),
            "existingBindingCount": len(members),
            "proposedBindingCount": 1,
            "proposedBinding": proposed.label,
            "knownExpandedResources": expanded,
            "inventoryComplete": inventory_complete,
        },
        remediation=Remediation(add=(proposed,), remove=member_ids),
        estimated_savings=len(members) - 1,
        access_expansion=True,
        warnings=tuple(warnings),
    )


def find_consolidations(
    survivors: Sequence[RoleBinding],
    inventory: Sequence[RoleBinding],
    config: Config,
    inventory_complete: bool,
) -> List[Finding]:
    """Run RB004 and/or RB005 on the bindings that survive SAFE removals.

    Only resource types with verified PREFIXED semantics are considered; a
    consolidation on an unverified type would propose semantics the tool cannot
    vouch for.
    """
    enabled = config.enabled_rules
    findings: List[Finding] = []

    for members in group_bindings(survivors).values():
        with_resource = [b for b in members if b.resource is not None]
        if not with_resource or with_resource[0].resource.type not in config.verified_pattern_semantics:
            continue
        literals = [b for b in with_resource if b.resource.pattern_type is PatternType.LITERAL]
        prefixes = [b for b in with_resource if b.resource.pattern_type is PatternType.PREFIXED]
        existing_prefixes = [b.resource.name for b in prefixes]

        if RULE_LITERALS in enabled and literals:
            names = [b.resource.name for b in literals]
            candidates = [
                c for c in candidate_prefixes(names, config) if not _covered_by_existing(c, existing_prefixes)
            ]
            assignment = assign_to_prefixes(names, candidates, config.minimum_bindings_to_merge, _literal_matches)
            for candidate, assigned in assignment.items():
                chosen = [b for b in literals if b.resource.name in assigned]
                findings.append(
                    _build_finding(RULE_LITERALS, members, candidate, chosen, inventory, config, inventory_complete)
                )

        if RULE_PREFIXES in enabled and prefixes:
            names = existing_prefixes
            candidates = [
                c for c in candidate_prefixes(names, config) if not _covered_by_existing(c, existing_prefixes)
            ]
            assignment = assign_to_prefixes(names, candidates, config.minimum_prefixes_to_merge, _prefix_matches)
            for candidate, assigned in assignment.items():
                chosen = [b for b in prefixes if b.resource.name in assigned]
                findings.append(
                    _build_finding(RULE_PREFIXES, members, candidate, chosen, inventory, config, inventory_complete)
                )
    return findings
