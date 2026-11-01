package governance.exceptions

# Scoped exceptions are resolved by Governance (principal/team/environment already filtered) and passed in input.
# A violation is exempted when it is bypassable and at least one exception matches its policy, resource and action.

matching(v) := {e.id |
	some e in input.exceptions
	e.policyId == v.policyId
	v.bypassable == true
	principal_matches(e)
	env_matches(e)
	resource_matches(e, v)
	action_matches(e)
}

principal_matches(e) if e.principalId == "*"

principal_matches(e) if e.principalId == input.principal.id

env_matches(e) if e.environment == "*"

env_matches(e) if e.environment == input.environment

resource_matches(e, _) if e.resourcePattern == "*"

resource_matches(e, v) if glob.match(e.resourcePattern, [], v.resourceName)

# Action scope only applies at plan stage; at request stage there are no actions yet.
action_matches(e) if count(e.actionTypes) == 0

action_matches(_) if {
	input.stage == "REQUEST"
}

action_matches(e) if {
	input.stage == "ACTION_PLAN"
	some a in input.actions
	a.actionType in e.actionTypes
}
