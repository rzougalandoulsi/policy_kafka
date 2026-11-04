import unittest

from analyzer.coverage import binding_covers, pattern_covers
from analyzer.models import Classification, Coverage, PatternType, ResourcePattern
from analyzer.validator import validate_document
from helpers import analyze, binding, config, document, findings_for


def pat(name, pattern="LITERAL", rtype="Topic"):
    return ResourcePattern(rtype, name, PatternType(pattern))


class PatternCoversTests(unittest.TestCase):
    def test_prefix_covers_literal(self):
        self.assertTrue(pattern_covers(pat("team-a.", "PREFIXED"), pat("team-a.orders")))

    def test_common_substring_is_not_coverage(self):
        self.assertFalse(pattern_covers(pat("team-a.", "PREFIXED"), pat("team-ab.orders")))
        self.assertFalse(pattern_covers(pat("team-a.", "PREFIXED"), pat("x.team-a.orders")))

    def test_literal_never_covers(self):
        self.assertFalse(pattern_covers(pat("team-a.orders"), pat("team-a.orders")))
        self.assertFalse(pattern_covers(pat("team-a.orders"), pat("team-a.", "PREFIXED")))

    def test_prefix_covers_strictly_narrower_prefix_only(self):
        self.assertTrue(pattern_covers(pat("team-a.", "PREFIXED"), pat("team-a.orders.", "PREFIXED")))
        self.assertFalse(pattern_covers(pat("team-a.", "PREFIXED"), pat("team-a.", "PREFIXED")))
        self.assertFalse(pattern_covers(pat("team-a.orders.", "PREFIXED"), pat("team-a.", "PREFIXED")))

    def test_empty_prefix_never_covers(self):
        self.assertFalse(pattern_covers(pat("", "PREFIXED"), pat("anything")))

    def test_resource_types_are_never_mixed(self):
        self.assertFalse(pattern_covers(pat("team-a.", "PREFIXED", "Topic"), pat("team-a.cg", rtype="Group")))


class BindingCoversTests(unittest.TestCase):
    def _bindings(self, records):
        return validate_document(document(records), config()).bindings

    def test_covers_only_with_identical_identity_dimensions(self):
        cover, target = self._bindings([binding("p", "team-a.", "PREFIXED"), binding("l", "team-a.orders")])
        self.assertIs(binding_covers(cover, target, {"Topic"}), Coverage.COVERS)

    def test_different_role_scope_or_principal_is_not_covered(self):
        cover, by_role, by_cluster, by_env, by_principal = self._bindings([
            binding("p", "team-a.", "PREFIXED"),
            binding("l1", "team-a.orders", role="DeveloperWrite"),
            binding("l2", "team-a.orders", cluster="lkc-2"),
            binding("l3", "team-a.orders", env="env-2"),
            binding("l4", "team-a.orders", principal="User:sa-2"),
        ])
        for target in (by_role, by_cluster, by_env, by_principal):
            self.assertIs(binding_covers(cover, target, {"Topic"}), Coverage.NOT_COVERED)

    def test_unverified_resource_type_is_indeterminate(self):
        cover, target = self._bindings([
            binding("p", "team-a.", "PREFIXED", rtype="Subject"),
            binding("l", "team-a.orders", rtype="Subject"),
        ])
        self.assertIs(binding_covers(cover, target, {"Topic"}), Coverage.INDETERMINATE)
        self.assertIs(binding_covers(cover, target, {"Subject"}), Coverage.COVERS)

    def test_no_resource_bindings_are_never_covered(self):
        a, b = self._bindings([
            binding("a", rtype=None, role="EnvironmentAdmin", kind="ENVIRONMENT"),
            binding("b", rtype=None, role="EnvironmentAdmin", kind="ENVIRONMENT", principal="User:sa-2"),
        ])
        self.assertIs(binding_covers(a, b, {"Topic"}), Coverage.NOT_COVERED)


class PrefixCoverageRuleTests(unittest.TestCase):
    def test_literal_covered_by_prefixed(self):
        result = analyze(document([binding("p", "team-a.", "PREFIXED"), binding("l", "team-a.orders")]))
        found = findings_for(result, "RB002")
        self.assertEqual(len(found), 1)
        finding = found[0]
        self.assertIs(finding.classification, Classification.SAFE)
        self.assertEqual(finding.evidence["coveringBindingId"], "p")
        self.assertEqual(finding.evidence["redundantBindingIds"], ["l"])
        self.assertEqual(finding.remediation.remove, ("l",))
        self.assertEqual(finding.remediation.keep, ("p",))
        self.assertEqual(finding.estimated_savings, 1)
        self.assertFalse(finding.access_expansion)
        self.assertEqual(result.safe_plan.remove, ("l",))
        self.assertEqual(result.statistics["redundantLiteralBindings"], 1)

    def test_literal_not_covered_when_prefix_differs(self):
        result = analyze(document([binding("p", "team-a.", "PREFIXED"), binding("l", "team-ab.orders")]))
        self.assertEqual(result.findings, ())

    def test_prefixed_covered_by_broader_prefixed(self):
        result = analyze(document([
            binding("p1", "team-a.", "PREFIXED"),
            binding("p2", "team-a.orders.", "PREFIXED"),
            binding("p3", "team-a.orders.v2", "PREFIXED"),
        ]))
        found = findings_for(result, "RB003")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].remediation.keep, ("p1",))
        self.assertEqual(found[0].remediation.remove, ("p2", "p3"))
        self.assertEqual(found[0].estimated_savings, 2)
        self.assertEqual(result.statistics["redundantPrefixedBindings"], 2)

    def test_multiple_covering_prefixes_choose_broadest_deterministically(self):
        result = analyze(document([
            binding("narrow", "team-a.orders.", "PREFIXED"),
            binding("broad", "team-a.", "PREFIXED"),
            binding("lit", "team-a.orders.v2"),
        ]))
        literal = findings_for(result, "RB002")[0]
        self.assertEqual(literal.evidence["coveringBindingId"], "broad")
        self.assertEqual(literal.evidence["redundantBindings"][0]["alternativeCoveringBindingIds"], ["narrow"])
        prefixed = findings_for(result, "RB003")[0]
        self.assertEqual(prefixed.remediation.remove, ("narrow",))
        self.assertEqual(set(result.safe_plan.remove), {"lit", "narrow"})
        self.assertEqual(result.safe_plan.keep, ("broad",))
        self.assertFalse(set(result.safe_plan.remove) & set(result.safe_plan.keep))

    def test_multiple_redundant_bindings_in_one_group(self):
        result = analyze(document([
            binding("p", "team-a.", "PREFIXED"),
            binding("l1", "team-a.orders"),
            binding("l2", "team-a.payments"),
            binding("l3", "team-a.inventory"),
        ]))
        found = findings_for(result, "RB002")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].remediation.remove, ("l1", "l2", "l3"))
        self.assertEqual(found[0].estimated_savings, 3)

    def test_no_coverage_across_clusters_environments_or_roles(self):
        result = analyze(document([
            binding("p", "team-a.", "PREFIXED"),
            binding("l1", "team-a.orders", cluster="lkc-2"),
            binding("l2", "team-a.orders", env="env-2", cluster="lkc-3"),
            binding("l3", "team-a.orders", role="DeveloperWrite"),
            binding("l4", "team-a.orders", principal="User:sa-2"),
        ]))
        self.assertEqual(result.findings, ())

    def test_resource_types_are_never_mixed(self):
        result = analyze(document([
            binding("p", "team-a.", "PREFIXED", rtype="Topic"),
            binding("g", "team-a.consumer", rtype="Group"),
            binding("t", "team-a.tx", rtype="TransactionalId"),
        ]))
        self.assertEqual(result.findings, ())

    def test_unverified_resource_type_yields_review_not_safe(self):
        result = analyze(document([
            binding("p", "team-a.", "PREFIXED", rtype="Subject"),
            binding("l", "team-a.orders-value", rtype="Subject"),
        ]))
        finding = findings_for(result, "RB002")[0]
        self.assertIs(finding.classification, Classification.REVIEW)
        self.assertEqual(finding.evidence["coverage"], "INDETERMINATE")
        self.assertEqual(result.safe_plan.remove, ())
        self.assertEqual(result.review_plan.remove, ("l",))
        self.assertEqual(result.statistics["potentialReviewSavings"], 1)

    def test_duplicate_rows_of_a_covered_binding_are_evidence_only(self):
        result = analyze(document([
            binding("p", "team-a.", "PREFIXED"),
            binding("l1", "team-a.orders"),
            binding("l2", "team-a.orders"),
        ]))
        finding = findings_for(result, "RB002")[0]
        self.assertEqual(finding.remediation.remove, ("l1",))
        self.assertEqual(finding.evidence["redundantBindings"][0]["duplicateRowIds"], ["l2"])
        self.assertEqual(result.safe_plan.estimated_savings, 1)

    def test_rule_filter_disables_rb003(self):
        doc = document([binding("p1", "team-a.", "PREFIXED"), binding("p2", "team-a.orders.", "PREFIXED"),
                        binding("l", "team-a.x")])
        result = analyze(doc, config(rules={"RB003": False}))
        self.assertEqual(findings_for(result, "RB003"), [])
        self.assertEqual(len(findings_for(result, "RB002")), 1)


if __name__ == "__main__":
    unittest.main()
