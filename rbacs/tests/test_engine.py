import csv
import io
import json
import random
import tempfile
import unittest
from pathlib import Path

from analyzer.reporting import csv_report, json_report, terminal
from helpers import analyze, binding, config, document


def _mixed_inventory():
    return document([
        binding("p", "team-a.", "PREFIXED"),
        binding("l1", "team-a.orders"),
        binding("l2", "team-a.orders"),
        binding("n", "team-a.orders.", "PREFIXED"),
        binding("x", "team-ab.orders"),
        binding("b1", "team-b.orders", principal="User:sa-2"),
        binding("b2", "team-b.payments", principal="User:sa-2"),
        binding("b3", "team-b.inventory", principal="User:sa-2"),
        binding("q1", "team-a.events.", "PREFIXED", principal="User:sa-3"),
        binding("q2", "team-a.metrics.", "PREFIXED", principal="User:sa-3"),
        binding("env", rtype=None, role="EnvironmentAdmin", kind="ENVIRONMENT", principal="User:u-1"),
    ])


class EngineTests(unittest.TestCase):
    def test_no_optimization_opportunities(self):
        result = analyze(document([
            binding("a", "orders"),
            binding("b", "payments"),
            binding("c", "orders", role="DeveloperWrite"),
            binding("d", "team-a.", "PREFIXED", principal="User:sa-2"),
        ]))
        self.assertEqual(result.findings, ())
        self.assertEqual(result.recommendations, ())
        self.assertEqual(result.safe_plan.remove, ())
        self.assertEqual(result.statistics["confirmedSafeRemovals"], 0)
        self.assertEqual(result.statistics["potentialReductionPercent"], 0.0)
        self.assertEqual(result.summary.unique_bindings, 4)

    def test_results_are_deterministic_regardless_of_input_order(self):
        doc = _mixed_inventory()
        cfg = config(consolidation={"allowAccessExpansion": True})
        baseline = json_report.build_report(analyze(doc, cfg))
        for seed in range(5):
            shuffled = dict(doc)
            shuffled["bindings"] = list(doc["bindings"])
            random.Random(seed).shuffle(shuffled["bindings"])
            report = json_report.build_report(analyze(shuffled, cfg))
            for key in ("findings", "recommendations", "safeRemediationPlan", "reviewOpportunities",
                        "statistics", "inputSummary", "analyzedBindings"):
                self.assertEqual(report[key], baseline[key], key)

    def test_finding_ids_are_stable_across_runs(self):
        doc = _mixed_inventory()
        first = [f.id for f in analyze(doc).findings]
        second = [f.id for f in analyze(json.loads(json.dumps(doc))).findings]
        self.assertEqual(first, second)
        self.assertTrue(all(i.startswith(("RB00",)) for i in first))

    def test_removals_are_never_double_counted(self):
        doc = document(
            [binding("p", "team-a.", "PREFIXED"), binding("l1", "team-a.orders"), binding("l2", "team-a.orders")],
            bindingIdsAreConfluentBindingIds=True,
        )
        result = analyze(doc)
        rule_ids = sorted(f.rule_id for f in result.findings)
        self.assertEqual(rule_ids, ["RB001", "RB002"])
        self.assertEqual(result.safe_plan.remove, ("l1", "l2"))
        self.assertEqual(result.safe_plan.keep, ("p",))
        self.assertEqual(result.safe_plan.estimated_savings, 2)
        self.assertEqual(result.statistics["confirmedSafeRemovals"], 2)
        self.assertEqual(sum(f.estimated_savings for f in result.findings), 2)
        statuses = {r.rule_id: r.status.value for r in result.recommendations}
        self.assertEqual(statuses, {"RB001": "APPROVED_SAFE", "RB002": "APPROVED_SAFE"})
        self.assertEqual(result.summary.unique_bindings, 2)

    def test_safe_plan_never_keeps_and_removes_the_same_binding(self):
        result = analyze(_mixed_inventory(), config(consolidation={"allowAccessExpansion": True}))
        self.assertFalse(set(result.safe_plan.remove) & set(result.safe_plan.keep))
        self.assertFalse(set(result.review_plan.remove) & set(result.safe_plan.remove))
        self.assertFalse(set(result.review_plan.remove) & set(result.safe_plan.keep))
        self.assertEqual(len(result.safe_plan.remove), len(set(result.safe_plan.remove)))

    def test_safe_findings_never_expand_access_or_add_bindings(self):
        result = analyze(_mixed_inventory(), config(consolidation={"allowAccessExpansion": True}))
        for finding in result.findings:
            if finding.classification.value == "SAFE":
                self.assertFalse(finding.access_expansion)
                self.assertEqual(finding.remediation.add, ())
                self.assertTrue(finding.remediation.remove)
        self.assertEqual(result.safe_plan.add, ())

    def test_statistics_match_plan(self):
        result = analyze(_mixed_inventory())
        stats = result.statistics
        self.assertEqual(stats["confirmedSafeRemovals"], len(result.safe_plan.remove))
        self.assertEqual(stats["redundantLiteralBindings"] + stats["redundantPrefixedBindings"]
                         + stats["removableDuplicateBindings"], stats["confirmedSafeRemovals"])
        self.assertEqual(stats["duplicateExportRows"], 1)
        self.assertEqual(stats["literalConsolidations"], 1)
        self.assertEqual(stats["prefixConsolidations"], 1)
        self.assertEqual(stats["accessExpandingCandidates"], 2)
        self.assertEqual(stats["recommendationStatusCounts"]["EXCLUDED_BY_POLICY"], 2)
        expected = round(2 / result.summary.unique_bindings * 100, 1)
        self.assertEqual(stats["potentialReductionPercent"], expected)

    def test_rule_filter_limits_findings(self):
        result = analyze(_mixed_inventory(), config().with_rules(["RB001"]))
        self.assertEqual({f.rule_id for f in result.findings}, {"RB001"})

    def test_input_summary_counts(self):
        result = analyze(_mixed_inventory())
        summary = result.summary
        self.assertEqual(summary.total_records, 11)
        self.assertEqual(summary.unique_bindings, 10)
        self.assertEqual(summary.principals, 4)
        self.assertEqual(summary.literal_bindings, 5)
        self.assertEqual(summary.prefixed_bindings, 4)
        self.assertEqual(summary.no_resource_bindings, 1)
        self.assertEqual(summary.bindings_per_role["EnvironmentAdmin"], 1)
        self.assertEqual(summary.bindings_per_resource_type["<none>"], 1)
        self.assertEqual(summary.bindings_per_scope["ENVIRONMENT/org-1/env-1"], 1)

    def test_reports_are_written_and_well_formed(self):
        result = analyze(_mixed_inventory())
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "nested" / "out"
            json_path = json_report.write_report(result, out / "report.json")
            csv_path = csv_report.write_recommendations(result, out / "recommendations.csv")
            report = json.loads(json_path.read_text())
            for key in ("metadata", "inputSummary", "findings", "recommendations", "safeRemediationPlan",
                        "reviewOpportunities", "statistics", "skippedRecords", "warnings"):
                self.assertIn(key, report)
            self.assertTrue(report["metadata"]["readOnly"])
            rows = list(csv.DictReader(io.StringIO(csv_path.read_text())))
            self.assertEqual(len(rows), len(result.recommendations))
            self.assertEqual(rows[0]["rule_id"], result.recommendations[0].rule_id)
        text = terminal.render(result, verbose=True)
        self.assertIn("No changes have been applied.", text)
        self.assertIn("FINDINGS", text)

    def test_csv_escapes_commas_and_quotes(self):
        doc = document([
            binding("p", 'team-a.', "PREFIXED", principal='User:"quoted, name"'),
            binding("l", "team-a.orders", principal='User:"quoted, name"'),
        ])
        result = analyze(doc)
        with tempfile.TemporaryDirectory() as tmp:
            path = csv_report.write_recommendations(result, Path(tmp) / "r.csv")
            rows = list(csv.DictReader(io.StringIO(path.read_text())))
        self.assertEqual(rows[0]["principal"], 'User:"quoted, name"')


if __name__ == "__main__":
    unittest.main()
