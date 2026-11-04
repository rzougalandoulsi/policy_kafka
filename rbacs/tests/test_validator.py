import unittest
from pathlib import Path

from analyzer.errors import InputError
from analyzer.loader import load_inventory
from analyzer.models import RecordStatus
from analyzer.validator import validate_document
from helpers import binding, config, document, scope

FIXTURES = Path(__file__).parent / "fixtures"


class ValidatorTests(unittest.TestCase):
    def test_invalid_json_is_input_error(self):
        with self.assertRaises(InputError) as ctx:
            load_inventory(FIXTURES / "malformed.json")
        self.assertIn("not valid JSON", str(ctx.exception))

    def test_missing_input_file_is_input_error(self):
        with self.assertRaises(InputError):
            load_inventory(FIXTURES / "does-not-exist.json")

    def test_missing_metadata_is_input_error(self):
        with self.assertRaises(InputError):
            validate_document({"bindings": []}, config())

    def test_missing_organization_id_is_input_error(self):
        with self.assertRaises(InputError):
            validate_document({"metadata": {"exportedAt": "x"}, "bindings": []}, config())

    def test_missing_bindings_is_input_error(self):
        with self.assertRaises(InputError):
            validate_document({"metadata": {"organizationId": "org-1"}}, config())

    def test_missing_required_fields_are_skipped_as_invalid(self):
        records = [
            {"id": "r1", "role": "DeveloperRead", "scope": scope(), "resource": None},
            {"id": "r2", "principal": "User:sa-1", "scope": scope()},
            {"id": "r3", "principal": "User:sa-1", "role": "DeveloperRead"},
            {"id": "r4", "principal": "User:sa-1", "role": "DeveloperRead", "scope": scope(cluster=None)},
            {"id": "r5", "principal": "User:sa-1", "role": "DeveloperRead", "scope": scope(),
             "resource": {"type": "Topic", "name": "x"}},
            "not-an-object",
        ]
        outcome = validate_document(document(records), config())
        self.assertEqual(outcome.bindings, ())
        self.assertEqual(len(outcome.skipped), 6)
        for record in outcome.skipped:
            self.assertIs(record.status, RecordStatus.INVALID)
        reasons = {s.source_id: " ".join(s.reasons) for s in outcome.skipped}
        self.assertIn("principal", reasons["r1"])
        self.assertIn("role", reasons["r2"])
        self.assertIn("scope", reasons["r3"])
        self.assertIn("clusterId", reasons["r4"])
        self.assertIn("patternType", reasons["r5"])

    def test_unsupported_scope_type_is_preserved_as_skipped(self):
        record = binding("r1", "orders")
        record["scope"] = {"type": "SCHEMA_REGISTRY", "organizationId": "org-1", "environmentId": "env-1"}
        outcome = validate_document(document([record, binding("r2", "orders")]), config())
        self.assertEqual([b.source_id for b in outcome.bindings], ["r2"])
        self.assertEqual(len(outcome.skipped), 1)
        self.assertIs(outcome.skipped[0].status, RecordStatus.UNSUPPORTED)
        self.assertIs(outcome.skipped[0].raw, record)

    def test_unsupported_pattern_type_is_skipped(self):
        outcome = validate_document(document([binding("r1", "orders.*", "MATCH")]), config())
        self.assertEqual(outcome.bindings, ())
        self.assertIs(outcome.skipped[0].status, RecordStatus.UNSUPPORTED)

    def test_duplicate_source_ids_are_invalid_but_others_continue(self):
        outcome = validate_document(
            document([binding("dup", "a"), binding("dup", "b"), binding("ok", "c")]), config()
        )
        self.assertEqual([b.source_id for b in outcome.bindings], ["ok"])
        self.assertEqual({s.source_id for s in outcome.skipped}, {"dup"})
        self.assertTrue(all(s.status is RecordStatus.INVALID for s in outcome.skipped))

    def test_missing_id_is_synthesized_with_warning(self):
        record = binding("r1", "a")
        del record["id"]
        outcome = validate_document(document([record]), config())
        self.assertEqual(outcome.bindings[0].source_id, "row-0")
        self.assertTrue(any("synthesized row-0" in w for w in outcome.warnings))

    def test_empty_prefix_is_unsupported_and_empty_literal_is_invalid(self):
        outcome = validate_document(
            document([binding("p", "", "PREFIXED"), binding("l", "", "LITERAL")]), config()
        )
        self.assertEqual(outcome.bindings, ())
        statuses = {s.source_id: s.status for s in outcome.skipped}
        self.assertIs(statuses["p"], RecordStatus.UNSUPPORTED)
        self.assertIs(statuses["l"], RecordStatus.INVALID)

    def test_partial_inventory_and_missing_metadata_fields_warn(self):
        doc = {"metadata": {"organizationId": "org-1"}, "bindings": [binding("r1", "a")]}
        outcome = validate_document(doc, config())
        self.assertEqual(len(outcome.bindings), 1)
        self.assertFalse(outcome.metadata["completeInventory"])
        self.assertIsNone(outcome.metadata["exportedAt"])
        joined = " ".join(outcome.warnings)
        self.assertIn("exportedAt", joined)
        self.assertIn("completeInventory", joined)
        self.assertIn("partial", joined)

    def test_ambiguous_scope_fails_by_default(self):
        record = binding("r1", "a", kind="ENVIRONMENT")
        record["scope"]["clusterId"] = "lkc-1"
        with self.assertRaises(InputError) as ctx:
            validate_document(document([record]), config())
        self.assertIn("ambiguous", str(ctx.exception))

    def test_ambiguous_scope_is_skipped_when_configured(self):
        record = binding("r1", "a", kind="ENVIRONMENT")
        record["scope"]["clusterId"] = "lkc-1"
        outcome = validate_document(
            document([record]), config(analysis={"failOnAmbiguousScope": False})
        )
        self.assertEqual(outcome.bindings, ())
        self.assertIs(outcome.skipped[0].status, RecordStatus.UNSUPPORTED)

    def test_organization_mismatch_is_ambiguous(self):
        outcome = validate_document(
            document([binding("r1", "a", org="org-other")]),
            config(analysis={"failOnAmbiguousScope": False}),
        )
        self.assertEqual(outcome.bindings, ())
        self.assertIn("metadata.organizationId", outcome.skipped[0].reasons[0])

    def test_conflicting_crns_for_one_scope_are_ambiguous(self):
        doc = document([binding("r1", "a", crn="crn://one"), binding("r2", "b", crn="crn://two")])
        with self.assertRaises(InputError):
            validate_document(doc, config())

    def test_unknown_resource_types_and_no_resource_are_analyzable(self):
        records = [
            binding("r1", "lkc-1", rtype="Cluster"),
            binding("r2", rtype=None, kind="ORGANIZATION", role="OrganizationAdmin"),
            binding("r3", rtype=None, kind="ENVIRONMENT", role="EnvironmentAdmin"),
        ]
        outcome = validate_document(document(records), config())
        self.assertEqual(len(outcome.bindings), 3)
        self.assertEqual(outcome.skipped, ())
        self.assertIsNone(outcome.bindings[1].resource)
        self.assertEqual(outcome.bindings[1].scope.scope_key, "ORGANIZATION/org-1")


if __name__ == "__main__":
    unittest.main()
