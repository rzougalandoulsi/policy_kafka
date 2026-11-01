package governance.core

import data.governance.core.authz
import data.governance.core.exceptions

# Entry point: POST /v1/data/governance/core/decision
#
# The decision is composed from six independently versioned bundles. Core owns the composition, the global
# prohibitions (authz) and exception matching; the five domain bundles each expose `violations`, `approvals`,
# `catalog` and `contract_version` under their own root. Core references them dynamically (never imports them),
# so a missing or incompatible bundle shows up as `ready == false` instead of a compile error. Governance treats
# `ready == false` as a policy-engine error (fail closed).
#
# Precedence (deterministic): HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW.
# Hard denies are never exemptable and never approvable. Only bypassable violations can be exempted.
# A violation or approval whose policy runs in shadow mode (configuration.enforcement[policyId] == "shadow") is
# reported with "enforcement": "shadow" and never changes the outcome; Governance records it as a shadow finding.

contract_version := "2.0"

required := ["manifests", "naming", "quotas", "resources", "changes"]

bundle_contract(ns) := v if {
	v := data.governance[ns].contract_version
}

loaded(ns) if bundle_contract(ns)

major(v) := split(v, ".")[0]

compatible(ns) if major(bundle_contract(ns)) == major(contract_version)

missing := {ns | some ns in required; not loaded(ns)}

incompatible := {ns | some ns in required; loaded(ns); not compatible(ns)}

default ready := false

ready if {
	count(missing) == 0
	count(incompatible) == 0
}

bundles := {ns: bundle_contract(ns) | some ns in required; loaded(ns)}

bundle_violations(ns) := vs if {
	vs := data.governance[ns].violations
} else := set()

bundle_approvals(ns) := a if {
	a := data.governance[ns].approvals
} else := set()

bundle_catalog(ns) := c if {
	c := data.governance[ns].catalog
} else := []

raw_violations := authz.violations | union({bundle_violations(ns) | some ns in required})

raw_approvals := union({bundle_approvals(ns) | some ns in required})

all_violations := [v |
	some raw in sorted(raw_violations)
	ids := exceptions.matching(raw)
	v := annotate(raw, ids)
]

annotate(raw, ids) := object.union(raw, {"exempted": false, "exceptionId": null}) if count(ids) == 0

annotate(raw, ids) := object.union(raw, {"exempted": true, "exceptionId": sort(ids)[0]}) if count(ids) > 0

# A missing enforcement field counts as "enforce" so a rule that forgets it can never fail open.
enforced(x) if object.get(x, "enforcement", "enforce") == "enforce"

blocking := [v | some v in all_violations; v.exempted == false; enforced(v)]

hard_denied if {
	some v in blocking
	v.severity == "HARD_DENY"
}

all_approvals := [] if hard_denied

all_approvals := sorted(raw_approvals) if not hard_denied

effective_approvals := [a | some a in all_approvals; enforced(a)]

decision := {
	"decision": outcome,
	"violations": all_violations,
	"approvals": all_approvals,
	"matchedPolicies": sort({v.policyId | some v in all_violations} | {a.policyId | some a in all_approvals}),
	"appliedExceptions": sort({v.exceptionId | some v in all_violations; v.exempted == true}),
	"ready": ready,
	"bundles": bundles,
	"revisions": revisions,
	"contractVersion": contract_version,
}

outcome := "ERROR" if not ready

outcome := "DENY" if {
	ready
	count(blocking) > 0
}

outcome := "REQUIRE_APPROVAL" if {
	ready
	count(blocking) == 0
	count(effective_approvals) > 0
}

outcome := "ALLOW" if {
	ready
	count(blocking) == 0
	count(effective_approvals) == 0
}

# Revisions of every bundle OPA has loaded (empty under `opa test`).
revisions := {name: rev | some name, b in data.system.bundles; rev := b.manifest.revision}

# Merged policy catalogue served through GET /api/v1/admin/policies (data.governance.core.policy_catalog).
# Core must not define rules named violations/approvals/catalog itself: data.governance[ns].<name> would then
# refer back to core and OPA rejects the composition as recursive.
policy_catalog := array.concat(authz.catalog, [c | some ns in required; some c in bundle_catalog(ns)])

catalog_ids := {p.policyId | some p in policy_catalog}

bypassable_ids := {p.policyId | some p in policy_catalog; p.bypassable == true}

# Stable ordering by policyId then resourceName (sort works on arrays of comparable values; use keys).
sorted(set) := [set_by_key[k] | some k in sort([k | some k, _ in set_by_key])] if {
	set_by_key := {sprintf("%s|%s|%s", [v.policyId, v.resourceType, v.resourceName]): v | some v in set}
}
