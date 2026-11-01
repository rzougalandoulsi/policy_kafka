package governance

import data.governance.approval
import data.governance.destructive
import data.governance.exceptions
import data.governance.limits
import data.governance.naming
import data.governance.rbac

# Entry point: POST /v1/data/governance/decision
#
# Precedence (deterministic): HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW.
# Hard denies are never exemptable and are never approvable. Only bypassable violations can be exempted.

raw_violations := naming.violations | rbac.violations | limits.violations | destructive.violations

violations := [v |
	some raw in sorted(raw_violations)
	ids := exceptions.matching(raw)
	v := annotate(raw, ids)
]

annotate(raw, ids) := object.union(raw, {"exempted": false, "exceptionId": null}) if count(ids) == 0

annotate(raw, ids) := object.union(raw, {"exempted": true, "exceptionId": sort(ids)[0]}) if count(ids) > 0

blocking := [v | some v in violations; v.exempted == false]

hard_denied if {
	some v in blocking
	v.severity == "HARD_DENY"
}

approvals := [] if hard_denied

approvals := sorted(approval.approvals) if not hard_denied

decision := {
	"decision": outcome,
	"violations": violations,
	"approvals": approvals,
	"matchedPolicies": sort({v.policyId | some v in violations} | {a.policyId | some a in approvals}),
	"appliedExceptions": sort({v.exceptionId | some v in violations; v.exempted == true}),
	"revision": revision,
}

outcome := "DENY" if count(blocking) > 0

outcome := "REQUIRE_APPROVAL" if {
	count(blocking) == 0
	count(approvals) > 0
}

outcome := "ALLOW" if {
	count(blocking) == 0
	count(approvals) == 0
}

# Bundle revision as loaded by OPA (absent under `opa test`).
default revision := ""

revision := data.system.bundles.governance.manifest.revision

# Stable ordering by policyId then resourceName (sort works on arrays of comparable values; use keys).
sorted(set) := [set_by_key[k] | some k in sort([k | some k, _ in set_by_key])] if {
	set_by_key := {sprintf("%s|%s|%s", [v.policyId, v.resourceType, v.resourceName]): v | some v in set}
}
