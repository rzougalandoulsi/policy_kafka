import unittest

from analyzer.models import Classification
from helpers import analyze, binding, document, findings_for


class DuplicateTests(unittest.TestCase):
    def test_exact_duplicates_are_data_quality_without_savings(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders"), binding("r3", "orders")]))
        found = findings_for(result, "RB001")
        self.assertEqual(len(found), 1)
        finding = found[0]
        self.assertIs(finding.classification, Classification.DATA_QUALITY)
        self.assertEqual(finding.estimated_savings, 0)
        self.assertEqual(finding.remediation.remove, ())
        self.assertEqual(finding.evidence["duplicateRowIds"], ["r1", "r2", "r3"])
        self.assertEqual(result.summary.unique_bindings, 1)
        self.assertEqual(result.statistics["duplicateExportRows"], 2)
        self.assertEqual(result.statistics["confirmedSafeRemovals"], 0)
        self.assertEqual(result.safe_plan.remove, ())

    def test_duplicates_are_safe_when_ids_are_confluent_binding_ids(self):
        doc = document(
            [binding("r1", "orders"), binding("r2", "orders"), binding("r3", "orders")],
            bindingIdsAreConfluentBindingIds=True,
        )
        result = analyze(doc)
        finding = findings_for(result, "RB001")[0]
        self.assertIs(finding.classification, Classification.SAFE)
        self.assertEqual(finding.estimated_savings, 2)
        self.assertEqual(finding.remediation.remove, ("r2", "r3"))
        self.assertEqual(finding.remediation.keep, ("r1",))
        self.assertEqual(result.safe_plan.remove, ("r2", "r3"))
        self.assertEqual(result.statistics["removableDuplicateBindings"], 2)

    def test_source_id_is_not_part_of_identity(self):
        result = analyze(document([binding("a-1", "orders"), binding("zz", "orders")]))
        self.assertEqual(len(findings_for(result, "RB001")), 1)

    def test_different_principals_are_not_duplicates(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders", principal="User:sa-2")]))
        self.assertEqual(findings_for(result, "RB001"), [])
        self.assertEqual(result.summary.unique_bindings, 2)

    def test_different_roles_are_not_duplicates(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders", role="DeveloperWrite")]))
        self.assertEqual(findings_for(result, "RB001"), [])

    def test_different_clusters_are_not_duplicates(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders", cluster="lkc-2")]))
        self.assertEqual(findings_for(result, "RB001"), [])

    def test_different_environments_are_not_duplicates(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders", env="env-2")]))
        self.assertEqual(findings_for(result, "RB001"), [])

    def test_different_scope_types_are_not_duplicates(self):
        doc = document([
            binding("r1", rtype=None, role="EnvironmentAdmin", kind="ENVIRONMENT"),
            binding("r2", rtype=None, role="EnvironmentAdmin", kind="ORGANIZATION"),
        ])
        result = analyze(doc)
        self.assertEqual(findings_for(result, "RB001"), [])

    def test_different_pattern_types_are_not_duplicates(self):
        result = analyze(document([binding("r1", "orders"), binding("r2", "orders", "PREFIXED")]))
        self.assertEqual(findings_for(result, "RB001"), [])

    def test_duplicate_rows_never_count_as_quota_savings(self):
        doc = document([binding("r1", "orders")] + [binding(f"r{i}", "orders") for i in range(2, 12)])
        result = analyze(doc)
        self.assertEqual(result.statistics["duplicateExportRows"], 10)
        self.assertEqual(result.statistics["estimatedSafeSavings"], 0)
        self.assertEqual(result.statistics["potentialReductionPercent"], 0.0)
        rec = result.recommendations[0]
        self.assertEqual(rec.status.value, "DATA_QUALITY")
        self.assertEqual(rec.estimated_savings, 0)


if __name__ == "__main__":
    unittest.main()
