import unittest

from analyzer.models import Classification, PatternType, RecommendationStatus
from analyzer.rules.consolidation import assign_to_prefixes, boundary_prefixes, candidate_prefixes
from helpers import analyze, binding, config, document, findings_for


def literals(prefix, names, principal="User:sa-1", role="DeveloperWrite", start=1):
    return [binding(f"l{start + i}", f"{prefix}{n}", principal=principal, role=role) for i, n in enumerate(names)]


class ConsolidationRuleTests(unittest.TestCase):
    def test_multiple_literals_consolidate_into_authorized_prefix(self):
        result = analyze(document(literals("team-b.", ["orders", "payments", "inventory", "customers"])))
        found = findings_for(result, "RB004")
        self.assertEqual(len(found), 1)
        finding = found[0]
        self.assertIs(finding.classification, Classification.REVIEW)
        self.assertEqual(finding.estimated_savings, 3)
        self.assertTrue(finding.access_expansion)
        self.assertEqual(finding.evidence["candidatePrefix"], "team-b.")
        self.assertTrue(finding.evidence["authorizedPrefix"])
        self.assertEqual(finding.evidence["existingBindingCount"], 4)
        self.assertEqual(finding.evidence["proposedBindingCount"], 1)
        self.assertEqual(finding.evidence["knownExpandedResources"], [])
        proposed = finding.remediation.add[0]
        self.assertEqual(proposed.resource_name, "team-b.")
        self.assertIs(proposed.pattern_type, PatternType.PREFIXED)
        self.assertEqual(finding.remediation.remove, ("l1", "l2", "l3", "l4"))
        self.assertEqual(result.safe_plan.remove, ())

    def test_consolidation_below_minimum_threshold_is_not_proposed(self):
        result = analyze(document(literals("team-b.", ["orders", "payments"])))
        self.assertEqual(findings_for(result, "RB004"), [])
        lowered = analyze(document(literals("team-b.", ["orders", "payments"])),
                          config(consolidation={"minimumBindingsToMerge": 2}))
        self.assertEqual(len(findings_for(lowered, "RB004")), 1)

    def test_unauthorized_prefix_is_not_proposed_when_required(self):
        doc = document(literals("team-z.", ["orders", "payments", "inventory"]))
        self.assertEqual(findings_for(analyze(doc), "RB004"), [])
        derived = analyze(doc, config(consolidation={"requireAuthorizedPrefix": False}))
        finding = findings_for(derived, "RB004")[0]
        self.assertEqual(finding.evidence["candidatePrefix"], "team-z.")
        self.assertFalse(finding.evidence["authorizedPrefix"])
        self.assertTrue(any("not in authorizedPrefixes" in w for w in finding.warnings))

    def test_consolidation_that_expands_access_is_reported_and_excluded_by_policy(self):
        doc = document(
            literals("team-b.", ["orders", "payments", "inventory"])
            + [binding("other", "team-b.audit", principal="User:sa-9")]
        )
        result = analyze(doc)
        finding = findings_for(result, "RB004")[0]
        self.assertEqual(finding.evidence["knownExpandedResources"], ["team-b.audit"])
        self.assertTrue(finding.access_expansion)
        rec = next(r for r in result.recommendations if r.finding_id == finding.id)
        self.assertIs(rec.status, RecommendationStatus.EXCLUDED_BY_POLICY)
        self.assertEqual(result.review_plan.finding_ids, ())
        self.assertEqual(result.statistics["accessExpandingCandidates"], 1)

        allowed = analyze(doc, config(consolidation={"allowAccessExpansion": True}))
        rec = next(r for r in allowed.recommendations if r.rule_id == "RB004")
        self.assertIs(rec.status, RecommendationStatus.REVIEW)
        self.assertEqual(allowed.review_plan.estimated_savings, 2)
        self.assertEqual(allowed.safe_plan.estimated_savings, 0)

    def test_excluded_candidates_hidden_when_review_candidates_disabled(self):
        doc = document(literals("team-b.", ["orders", "payments", "inventory"]))
        result = analyze(doc, config(consolidation={"includeReviewCandidates": False}))
        self.assertEqual(len(findings_for(result, "RB004")), 1)
        self.assertEqual(result.recommendations, ())

    def test_overlapping_candidates_assign_each_literal_once(self):
        doc = document(
            literals("team-a.", ["orders", "payments", "inventory"])
            + literals("team-a.internal.", ["audit", "metrics", "events"], start=10)
        )
        result = analyze(doc, config(consolidation={"allowAccessExpansion": True}))
        found = {f.evidence["candidatePrefix"]: f for f in findings_for(result, "RB004")}
        self.assertEqual(set(found), {"team-a.", "team-a.internal."})
        self.assertEqual(found["team-a."].remediation.remove, ("l1", "l2", "l3"))
        self.assertEqual(found["team-a.internal."].remediation.remove, ("l10", "l11", "l12"))
        removed = [i for f in found.values() for i in f.remediation.remove]
        self.assertEqual(len(removed), len(set(removed)))
        self.assertEqual(set(result.review_plan.finding_ids), {f.id for f in found.values()})
        self.assertEqual(result.review_plan.estimated_savings, 4)

    def test_narrow_candidate_below_threshold_flows_to_broader_candidate(self):
        doc = document(
            literals("team-a.", ["orders", "payments", "inventory"])
            + literals("team-a.internal.", ["audit", "metrics"], start=10)
        )
        result = analyze(doc)
        found = findings_for(result, "RB004")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].evidence["candidatePrefix"], "team-a.")
        self.assertEqual(found[0].estimated_savings, 4)

    def test_overlapping_prefixes_consolidate(self):
        doc = document([
            binding("p1", "team-a.orders.", "PREFIXED"),
            binding("p2", "team-a.events.", "PREFIXED"),
        ])
        result = analyze(doc)
        found = findings_for(result, "RB005")
        self.assertEqual(len(found), 1)
        self.assertIs(found[0].classification, Classification.REVIEW)
        self.assertEqual(found[0].evidence["candidatePrefix"], "team-a.")
        self.assertEqual(found[0].estimated_savings, 1)
        self.assertTrue(found[0].access_expansion)

    def test_prefix_consolidation_not_proposed_when_broader_binding_exists(self):
        doc = document([
            binding("p0", "team-a.", "PREFIXED"),
            binding("p1", "team-a.orders.", "PREFIXED"),
            binding("p2", "team-a.events.", "PREFIXED"),
        ])
        result = analyze(doc)
        self.assertEqual(findings_for(result, "RB005"), [])
        self.assertEqual(findings_for(result, "RB003")[0].remediation.remove, ("p1", "p2"))

    def test_literal_consolidation_not_proposed_when_covering_binding_exists(self):
        doc = document([binding("p0", "team-a.", "PREFIXED", role="DeveloperWrite")]
                       + literals("team-a.", ["a", "b", "c"]))
        result = analyze(doc)
        self.assertEqual(findings_for(result, "RB004"), [])
        self.assertEqual(len(findings_for(result, "RB002")), 1)

    def test_consolidation_respects_group_boundaries(self):
        doc = document(
            literals("team-b.", ["a", "b"])
            + literals("team-b.", ["c"], role="DeveloperRead", start=10)
            + literals("team-b.", ["d"], principal="User:sa-2", start=20)
            + [binding("g1", "team-b.x", rtype="Group", role="DeveloperWrite")]
        )
        self.assertEqual(findings_for(analyze(doc), "RB004"), [])

    def test_consolidation_skips_unverified_resource_types(self):
        doc = document(literals("team-b.", ["a", "b", "c"]))
        for record in doc["bindings"]:
            record["resource"]["type"] = "Subject"
        self.assertEqual(findings_for(analyze(doc), "RB004"), [])

    def test_same_proposed_binding_from_two_rules_is_counted_once(self):
        doc = document(
            literals("team-a.", ["a", "b", "c"])
            + [binding("p1", "team-a.x.", "PREFIXED", role="DeveloperWrite"),
               binding("p2", "team-a.y.", "PREFIXED", role="DeveloperWrite")]
        )
        result = analyze(doc, config(consolidation={"allowAccessExpansion": True}))
        statuses = sorted(r.status.value for r in result.recommendations)
        self.assertEqual(statuses, ["DEFERRED", "REVIEW"])
        self.assertEqual(len(result.review_plan.add), 1)
        self.assertEqual(result.review_plan.estimated_savings, 2)


class CandidateHelperTests(unittest.TestCase):
    def test_boundary_prefixes_stop_on_delimiters_and_respect_minimum(self):
        self.assertEqual(boundary_prefixes("team-a.orders.v2", 3), ["team-", "team-a.", "team-a.orders."])
        self.assertEqual(boundary_prefixes("team-a.orders.v2", 6), ["team-a.", "team-a.orders."])
        self.assertEqual(boundary_prefixes("t.x", 3), [])
        self.assertEqual(boundary_prefixes("", 1), [])

    def test_candidate_prefixes_never_empty_or_arbitrary(self):
        cfg = config(consolidation={"requireAuthorizedPrefix": False, "minimumPrefixLength": 3})
        candidates = candidate_prefixes(["t", "te", "team", "team-a.orders"], cfg)
        self.assertNotIn("", candidates)
        self.assertNotIn("t", candidates)
        self.assertNotIn("team", candidates)
        self.assertIn("team-a.", candidates)

    def test_assignment_prefers_longest_qualifying_prefix(self):
        names = ["a.b.1", "a.b.2", "a.b.3", "a.c.1", "a.c.2", "a.c.3"]
        assignment = assign_to_prefixes(names, ["a.", "a.b.", "a.c."], 3, lambda p, n: n.startswith(p))
        self.assertEqual(assignment, {"a.b.": ["a.b.1", "a.b.2", "a.b.3"], "a.c.": ["a.c.1", "a.c.2", "a.c.3"]})
        assignment = assign_to_prefixes(names, ["a.", "a.b.", "a.c."], 4, lambda p, n: n.startswith(p))
        self.assertEqual(assignment, {"a.": sorted(names)})


if __name__ == "__main__":
    unittest.main()
