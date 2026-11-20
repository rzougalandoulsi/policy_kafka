"""Offline tests for the GitHub discovery/counting flow (no live GitHub access)."""
import base64
import importlib.util
import json
import re
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

# The package itself requires python-hcl2; mock HCL loading for isolated mechanics tests.
sys.modules.setdefault("hcl2", types.SimpleNamespace(load=lambda stream: json.loads(stream.read())))
SRC = Path(__file__).resolve().parents[1] / "scanner.py"
spec = importlib.util.spec_from_file_location("scanner_under_test", SRC)
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class ScannerTests(unittest.TestCase):
    def test_counts_including_rbacs_and_metadata(self):
        counts, unknown = scanner.count_items({
            "topics": [{"topic_name": "a"}, {"topic_name": "b"}],
            "schemas": [{"subject_name": "a-value"}],
            "pools": [{"pool_name": "p1"}],
            "pools_rbac": [{"role_name": "DeveloperRead"}] * 3,
            "topics_meta_datas": [{"name": "owner"}] * 2,
            "connectors": [{"name": "foo"}],
        }, "x/y/input.tfvars")
        self.assertEqual(counts["resource_total"], 4)
        self.assertEqual(counts["rbac_total"], 3)
        self.assertEqual(counts["metadata_total"], 2)
        self.assertEqual(unknown, {"connectors": 1})

    def test_discovery_paginates_filters(self):
        page1 = [{"name": "ttfe-cc-rs-a"}] + [{"name": "unrelated"}] * 99
        page2 = [{"name": "ttfe-cc-rs-b"}]
        with patch.object(scanner, "gh_get", side_effect=[page1, page2]) as gh:
            found = scanner.discover_repositories("test-org", "ttfe-cc-rs-")
        self.assertEqual([r["name"] for r in found], ["ttfe-cc-rs-a", "ttfe-cc-rs-b"])
        self.assertEqual(gh.call_count, 2)

    def test_scan_consistent_commit_and_counts(self):
        raw = json.dumps({"topics": [{"topic_name": "a"}], "pools_rbac": [{"role_name": "DeveloperRead"}]})
        responses = [
            {"sha": "commit123", "commit": {"tree": {"sha": "tree123"}}},
            {"truncated": False, "tree": [
                {"type": "blob", "path": "cacn/nprod/input.tfvars", "sha": "blob123"},
                {"type": "blob", "path": "README.md", "sha": "other"},
            ]},
            {"encoding": "base64", "content": base64.b64encode(raw.encode()).decode()},
        ]
        info = {"name": "ttfe-cc-rs-a", "default_branch": "main", "html_url": "https://github.com/o/r"}
        with patch.object(scanner, "gh_get", side_effect=responses) as gh:
            repo, files = scanner.scan_repository("o", info, "develop", re.compile(r"[^/]+/[^/]+/input\.tfvars"))
        self.assertEqual(repo["tfvars_files"], 1)
        self.assertEqual(repo["has_declarations"], True)
        self.assertEqual(repo["resource_total"], 1)
        self.assertEqual(repo["rbac_total"], 1)
        self.assertEqual(repo["commit_sha"], "commit123")
        self.assertEqual(files[0]["region"], "cacn")
        self.assertEqual(files[0]["environment"], "nprod")
        self.assertIn("tree123", gh.call_args_list[1].args[0])

    def test_no_tfvars_not_same_as_empty(self):
        info = {"name": "ttfe-cc-rs-empty", "default_branch": "main"}
        with patch.object(scanner, "gh_get", side_effect=[
            {"sha": "abc", "commit": {"tree": {"sha": "def"}}},
            {"truncated": False, "tree": []},
        ]):
            repo, files = scanner.scan_repository("o", info, "develop", re.compile(r"[^/]+/[^/]+/input\.tfvars"))
        self.assertEqual(repo["status"], "no_tfvars")
        self.assertFalse(files)

    def test_write_counts(self):
        repo = {"repository": "r", "branch": "develop", "commit_sha": "abc", "status": "ok",
                "archived": False, "github_url": "", "tfvars_files": 1, "regions": "cacn",
                "environments": "cacn/nprod", "has_declarations": True,
                **scanner.empty_counts(), "unknown_collections": "{}", "message": ""}
        repo["topics"] = repo["resource_total"] = 2
        file_row = {"repository": "r", "branch": "develop", "commit_sha": "abc", "status": "ok",
                    "file_path": "cacn/nprod/input.tfvars", "region": "cacn", "environment": "nprod",
                    **scanner.empty_counts(), "unknown_collections": "{}", "message": ""}
        file_row["topics"] = file_row["resource_total"] = 2
        with tempfile.TemporaryDirectory() as directory:
            scanner.write_outputs(Path(directory), [repo], [file_row], [], "org", "prefix")
            summary = json.loads((Path(directory) / "summary.json").read_text())
            self.assertEqual(summary["counts"]["resource_total"], 2)
            self.assertTrue((Path(directory) / "environment_counts.csv").exists())


if __name__ == "__main__":
    unittest.main()
