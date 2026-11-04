"""RB001: records that share one canonical binding identity."""

from __future__ import annotations

from typing import List, Mapping, Sequence

from ..models import BindingIdentity, Classification, Finding, Remediation, RoleBinding, finding_id

RULE_ID = "RB001"


def find_duplicates(
    groups: Mapping[BindingIdentity, Sequence[RoleBinding]],
    ids_are_confluent_binding_ids: bool,
) -> List[Finding]:
    """One finding per identity that appears more than once.

    Export rows are DATA_QUALITY with zero savings unless the exporter has
    declared that row ids are real Confluent binding ids, in which case the
    extra objects are SAFE to remove.
    """
    findings: List[Finding] = []
    for members in groups.values():
        if len(members) < 2:
            continue
        rows = sorted(members, key=lambda b: b.source_id)
        representative = rows[0]
        row_ids = tuple(b.source_id for b in rows)
        extra_ids = row_ids[1:]
        evidence = {
            "binding": representative.label,
            "duplicateRowIds": list(row_ids),
            "retainedBindingId": representative.source_id,
            "rowCount": len(rows),
            "idsAreConfluentBindingIds": ids_are_confluent_binding_ids,
        }
        if ids_are_confluent_binding_ids:
            findings.append(
                Finding(
                    id=finding_id(RULE_ID, *row_ids),
                    rule_id=RULE_ID,
                    classification=Classification.SAFE,
                    principal=representative.principal,
                    role=representative.role,
                    scope_key=representative.scope.scope_key,
                    resource_type=representative.resource.type if representative.resource else "",
                    reason=(
                        f"{len(rows)} distinct Confluent role bindings grant exactly the same access; "
                        f"{len(extra_ids)} can be removed"
                    ),
                    evidence=evidence,
                    remediation=Remediation(remove=extra_ids, keep=(representative.source_id,)),
                    estimated_savings=len(extra_ids),
                    access_expansion=False,
                )
            )
        else:
            findings.append(
                Finding(
                    id=finding_id(RULE_ID, *row_ids),
                    rule_id=RULE_ID,
                    classification=Classification.DATA_QUALITY,
                    principal=representative.principal,
                    role=representative.role,
                    scope_key=representative.scope.scope_key,
                    resource_type=representative.resource.type if representative.resource else "",
                    reason=(
                        f"{len(rows)} export rows share one canonical binding identity; the export does not "
                        "prove they are distinct Confluent objects, so no removal is proposed"
                    ),
                    evidence=evidence,
                    remediation=Remediation(keep=(representative.source_id,)),
                    estimated_savings=0,
                    access_expansion=False,
                    warnings=(
                        "Set metadata.bindingIdsAreConfluentBindingIds to true in the export if row ids "
                        "are Confluent role-binding ids; duplicates then become SAFE removals.",
                    ),
                )
            )
    return findings
