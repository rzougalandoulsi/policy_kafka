package governance.core.composition_test

import data.governance.core
import data.governance.fixtures as f

# Core composes five domain bundles. A missing or incompatible bundle must never produce an ALLOW.

test_ready_with_all_bundles if {
	core.ready
	count(core.missing) == 0
	count(core.bundles) == 5
}

test_missing_bundle_is_not_ready if {
	not core.ready with data.governance.naming as {}
	core.missing == {"naming"} with data.governance.naming as {}
}

test_missing_bundle_yields_error_decision if {
	d := core.decision with input as f.request("development", [f.topic("team-a.orders", 6)], []) with data.governance.quotas as {}
	d.decision == "ERROR"
	d.ready == false
}

test_incompatible_contract_major_is_not_ready if {
	not core.ready with data.governance.resources.contract_version as "9.0"
	core.incompatible == {"resources"} with data.governance.resources.contract_version as "9.0"
}

test_minor_contract_difference_is_compatible if {
	core.ready with data.governance.resources.contract_version as "2.7"
}

test_clean_manifest_allows_when_ready if {
	d := core.decision with input as f.request("development", [f.topic("team-a.orders", 6)], [])
	d.decision == "ALLOW"
	d.ready == true
	d.bundles.naming == "2.0"
	d.contractVersion == "2.0"
}
