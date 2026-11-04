# Confluent Cloud RBAC Optimization Analyzer

A read-only command-line tool that reads an exported inventory of Confluent
Cloud RBAC role bindings and reports where the number of bindings can be
reduced without changing who can do what.

It never calls Confluent Cloud, needs no credentials or network access, and
writes nothing except reports under the output directory.

## Requirements

- Bash 3.2 or newer (macOS, WSL, Linux)
- Python 3.9 or newer, standard library only
- `jq` is optional; when present it gives a fast JSON syntax check

## Usage

```bash
./analyze.sh --input ./examples/sample-inventory.json
./analyze.sh --input ./rbacs.json --config ./config/analyzer.json --output ./output
./analyze.sh --input ./rbacs.json --rules RB001,RB002,RB003 --format json --verbose
```

| Option | Meaning |
|---|---|
| `--input FILE` | exported inventory (required) |
| `--config FILE` | analyzer configuration, default `config/analyzer.json` |
| `--output DIR` | where `report.json` and `recommendations.csv` go, default `./output` |
| `--rules IDS` | comma-separated subset of `RB001..RB005` |
| `--format FMT` | `terminal`, `json`, `csv` or `all` (default); repeatable |
| `--verbose` | list every finding, skipped record and warning |

Exit codes: `0` success, `1` invalid input or config, `2` usage error, `3` unexpected failure.
Ordinary validation failures print one `error:` line and no stack trace.

## Input contract

The canonical input format is documented in `contracts/rbac-inventory.schema.json`
and illustrated by `examples/sample-inventory.json`. In short:

```json
{
  "metadata": {
    "exportedAt": "2026-11-04T12:00:00Z",
    "organizationId": "org-123",
    "completeInventory": true,
    "bindingIdsAreConfluentBindingIds": false
  },
  "bindings": [
    {
      "id": "rbac-001",
      "principal": "User:sa-123",
      "role": "DeveloperRead",
      "scope": {"type": "KAFKA_CLUSTER", "organizationId": "org-123",
                "environmentId": "env-123", "clusterId": "lkc-123", "crn": "crn://..."},
      "resource": {"type": "Topic", "name": "team-a.", "patternType": "PREFIXED"}
    }
  ]
}
```

Every record ends up in exactly one of three buckets:

| Bucket | Meaning | Examples |
|---|---|---|
| INVALID | cannot be trusted; preserved under `skippedRecords` | missing principal, duplicate `id`, empty LITERAL name |
| UNSUPPORTED | well formed but this release cannot reason about it safely | scope type `SCHEMA_REGISTRY`, pattern `MATCH`, empty PREFIXED name, ambiguous scope |
| analyzable | becomes a canonical binding | everything else, including unknown resource types and bindings without a resource |

An ambiguous scope (for example an `ENVIRONMENT` scope that carries a `clusterId`,
an organization id that differs from the metadata, or one structured scope exported
with two different CRNs) stops the run by default. Set
`analysis.failOnAmbiguousScope` to `false` to skip those records instead.

## Canonical identity

Two records are the same binding when principal, role, scope type, organization,
environment, cluster, resource type, resource name and pattern type all match.
The row `id` and the CRN are kept for traceability only and never take part in
identity. Every rule runs on one representative per identity, so duplicate export
rows can never inflate savings.

## Rules

| Rule | Detects | Classification |
|---|---|---|
| RB001 | records with identical canonical identity | DATA_QUALITY, or SAFE only when `metadata.bindingIdsAreConfluentBindingIds` is `true` |
| RB002 | LITERAL binding covered by a PREFIXED binding of the same principal, role, scope and resource type | SAFE, or REVIEW when the resource type is not in `verifiedPatternSemantics` |
| RB003 | PREFIXED binding covered by a strictly broader PREFIXED binding | SAFE, or REVIEW as above |
| RB004 | several LITERAL bindings that an authorized prefix could replace | REVIEW, always access-expanding |
| RB005 | several PREFIXED bindings that an authorized broader prefix could replace | REVIEW, always access-expanding |

Coverage is deliberately conservative: no role inheritance, no scope inheritance,
no coverage across clusters or environments, no mixing of resource types, and a
common substring is never a prefix. When several bindings cover the same target
the broadest prefix (then the lowest id) is chosen, so a kept binding is never
also removed.

A new prefix proposed by RB004 or RB005 may match resources that exist today but
are not in the inventory, or resources created later. The inventory alone cannot
prove the set of resources that would gain access, so these findings are never
SAFE. `knownExpandedResources` lists the names already visible in the inventory
that the prefix would newly match.

## Configuration

`config/analyzer.json`:

| Key | Meaning |
|---|---|
| `analysis.readOnly` | must stay `true` |
| `analysis.failOnAmbiguousScope` | stop on ambiguous scopes (`true`) or skip them (`false`) |
| `rules.RBnnn` | enable or disable a rule; `--rules` narrows further |
| `consolidation.minimumBindingsToMerge` | literals needed before RB004 proposes a prefix (default 3) |
| `consolidation.minimumPrefixesToMerge` | prefixes needed before RB005 proposes a broader prefix (default 2) |
| `consolidation.requireAuthorizedPrefix` | only propose prefixes from `authorizedPrefixes` (`true`), or also delimiter-bounded common prefixes (`false`) |
| `consolidation.minimumPrefixLength` | shortest derived prefix when `requireAuthorizedPrefix` is `false` |
| `consolidation.allowAccessExpansion` | `false` keeps access-expanding candidates out of the review plan (status `EXCLUDED_BY_POLICY`) |
| `consolidation.includeReviewCandidates` | still list excluded candidates in the reports |
| `authorizedPrefixes` | map of owner to approved prefix boundaries |
| `verifiedPatternSemantics` | resource types whose PREFIXED semantics are confirmed; others yield REVIEW |

## How the plan is built

1. SAFE findings are admitted in a fixed order (RB002, RB003, then RB001). Each
   binding id is removed at most once. A finding that would remove a binding
   another approved finding relies on is marked `CONFLICT` and left out.
2. REVIEW findings are ranked by savings and admitted greedily. One that touches
   a binding already claimed, or proposes a binding already proposed, is marked
   `DEFERRED` so savings are never summed across overlapping proposals.
3. `estimatedSafeSavings` is the size of the safe removal set.
   `potentialReviewSavings` is removals minus additions of the admitted review set.

Recommendation statuses: `APPROVED_SAFE`, `REVIEW`, `DEFERRED`,
`EXCLUDED_BY_POLICY`, `CONFLICT`, `DATA_QUALITY`.

## Outputs

- terminal summary on stdout
- `output/report.json`: metadata, input summary, findings, recommendations,
  safe remediation plan, review opportunities, statistics, analyzed bindings,
  skipped records, warnings
- `output/recommendations.csv`: one row per recommendation

Neither output contains executable deletion commands.

## Tests

```bash
python3 -m unittest discover -s tests -v
```

## Layout

```
analyze.sh                 Bash entry point (dependency and path checks, then python3 -m analyzer)
analyzer/models.py         immutable dataclasses, canonical identity, stable finding ids
analyzer/loader.py         file reading and configuration parsing
analyzer/validator.py      INVALID / UNSUPPORTED / analyzable classification
analyzer/coverage.py       pattern_covers and binding_covers (three-valued)
analyzer/rules/            RB001 duplicates, RB002/RB003 prefix coverage, RB004/RB005 consolidation
analyzer/engine.py         pipeline, conflict resolution, statistics
analyzer/reporting/        terminal, JSON and CSV writers
contracts/                 JSON Schema documenting the input contract
examples/                  sample inventory exercising every rule
tests/                     unittest suite and fixtures
```

## Extension points

- Phase 4 (role and scope intelligence): `binding_covers` in `analyzer/coverage.py`
  is the single place that decides role and scope equivalence; a permission
  matrix would replace its equality checks and `Coverage.INDETERMINATE` already
  models "unknown".
- Phase 5 (quota intelligence): every finding carries its scope key and savings,
  so savings can be aggregated per organization, environment or cluster.
- Phase 6 (simulation): `RemediationPlan` holds add, remove and keep lists that a
  simulator can apply to `AnalysisResult.bindings`.
- Phase 7 (governance integration): `run_analysis` takes a parsed document, so a
  CLI, API or Terraform-derived loader only needs to produce the same contract.
