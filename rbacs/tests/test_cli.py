import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "tests" / "fixtures"
SCRIPT = ROOT / "analyze.sh"


def run_cli(*args, cwd=None):
    return subprocess.run(
        ["bash", str(SCRIPT), *args],
        cwd=cwd or tempfile.gettempdir(),
        capture_output=True,
        text=True,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    )


class CliTests(unittest.TestCase):
    def test_basic_fixture_writes_reports_from_any_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "reports"
            proc = run_cli("--input", str(FIXTURES / "basic.json"), "--output", str(out), cwd=tmp)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("CONFLUENT CLOUD RBAC OPTIMIZATION ANALYZER", proc.stdout)
            self.assertIn("No changes have been applied.", proc.stdout)
            report = json.loads((out / "report.json").read_text())
            self.assertEqual(report["statistics"]["confirmedSafeRemovals"], 2)
            self.assertEqual(report["statistics"]["duplicateExportRows"], 1)
            self.assertEqual(sorted(report["safeRemediationPlan"]["remove"]), ["rbac-002", "rbac-004"])
            self.assertTrue((out / "recommendations.csv").exists())

    def test_overlapping_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = run_cli("--input", str(FIXTURES / "overlapping.json"), "--output", tmp,
                           "--format", "json", "--verbose")
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertNotIn("OPTIMIZATION ANALYZER", proc.stdout)
            report = json.loads((Path(tmp) / "report.json").read_text())
            by_rule = report["statistics"]["findingsByRule"]
            self.assertEqual(by_rule, {"RB001": 0, "RB002": 1, "RB003": 1, "RB004": 2, "RB005": 1})
            self.assertEqual(report["safeRemediationPlan"]["remove"], ["rbac-002", "rbac-003"])
            rb005 = next(f for f in report["findings"] if f["ruleId"] == "RB005")
            self.assertEqual(rb005["evidence"]["knownExpandedResources"], ["team-b.secrets"])
            self.assertTrue(all(r["status"] == "EXCLUDED_BY_POLICY" for r in report["recommendations"]
                                if r["ruleId"] in ("RB004", "RB005")))

    def test_invalid_fixture_exits_nonzero_without_traceback(self):
        proc = run_cli("--input", str(FIXTURES / "invalid.json"))
        self.assertEqual(proc.returncode, 1)
        self.assertIn("error:", proc.stderr)
        self.assertIn("organizationId", proc.stderr)
        self.assertNotIn("Traceback", proc.stderr)

    def test_malformed_json_exits_nonzero_without_traceback(self):
        proc = run_cli("--input", str(FIXTURES / "malformed.json"))
        self.assertEqual(proc.returncode, 1)
        self.assertIn("not valid JSON", proc.stderr)
        self.assertNotIn("Traceback", proc.stderr)

    def test_missing_input_file(self):
        proc = run_cli("--input", str(FIXTURES / "nope.json"))
        self.assertEqual(proc.returncode, 1)
        self.assertIn("not found", proc.stderr)

    def test_missing_input_option_is_usage_error(self):
        proc = run_cli("--format", "json")
        self.assertEqual(proc.returncode, 2)
        self.assertIn("--input is required", proc.stderr)

    def test_unknown_rule_is_usage_error(self):
        proc = run_cli("--input", str(FIXTURES / "basic.json"), "--rules", "RB001,RB999", "--format", "terminal")
        self.assertEqual(proc.returncode, 2)
        self.assertIn("RB999", proc.stderr)
        self.assertNotIn("Traceback", proc.stderr)

    def test_rules_filter(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = run_cli("--input", str(FIXTURES / "basic.json"), "--output", tmp, "--rules", "RB001",
                           "--format", "json")
            self.assertEqual(proc.returncode, 0, proc.stderr)
            report = json.loads((Path(tmp) / "report.json").read_text())
            self.assertEqual({f["ruleId"] for f in report["findings"]}, {"RB001"})
            self.assertEqual(report["metadata"]["enabledRules"], ["RB001"])

    def test_missing_config_file(self):
        proc = run_cli("--input", str(FIXTURES / "basic.json"), "--config", "/nonexistent/analyzer.json")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("config", proc.stderr)

    def test_help(self):
        proc = run_cli("--help")
        self.assertEqual(proc.returncode, 0)
        self.assertIn("--input", proc.stdout)

    def test_python_module_entry_point(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run(
                [sys.executable, "-m", "analyzer", "--input", str(FIXTURES / "basic.json"),
                 "--output", tmp, "--format", "csv"],
                cwd=str(ROOT), capture_output=True, text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertTrue((Path(tmp) / "recommendations.csv").exists())

    def test_source_fixture_is_not_modified(self):
        path = FIXTURES / "basic.json"
        before = path.read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            run_cli("--input", str(path), "--output", tmp)
        self.assertEqual(path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
