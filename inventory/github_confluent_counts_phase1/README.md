# Phase 1 — GitHub Confluent Cloud declaration counter

This scanner inventories existing Terraform declarations to plan migration to a governed self-service platform. It **does not** decide exemptions, create resources, apply Terraform, or make changes in GitHub or Confluent Cloud.

## Setup

- Python 3.10+
- GitHub CLI (`gh`) installed and authenticated (`gh auth login`; test with `gh auth status`)
- Access to the selected GitHub organization's private repositories (at minimum repository metadata and contents read)

```bash
python -m pip install -r requirements.txt
```

## Run

```bash
python scanner.py \
  --org Desjardins \
  --prefix ttfe-cc-rs- \
  --branch develop \
  --output ./inventory-output
```

`--branch develop` is the default. Set `--branch default` to use each repository's default branch. Use another org/prefix as needed. You can override the target path, which defaults to `<region>/<environment>/input.tfvars`, with `--pattern` (Python full-match regular expression). For example, match any `.tfvars` under a region/environment folder with `--pattern '[^/]+/[^/]+/.*\.tfvars'`.

## Mechanism

1. Fetch **all pages** from `GET /orgs/{org}/repos?type=all&per_page=100&page=N`, then locally select names starting with the prefix.
2. Sequentially process each matching repository. Resolve the configured branch to a commit and tree SHA.
3. Read a recursive Git tree, select `<region>/<environment>/input.tfvars`, fetch each matching Git blob. Never clone the repo.
4. Parse Terraform HCL with `python-hcl2` and count objects in the known top-level collections.
5. Export count-only summaries and error reports. Each repo is committed to the output only if **every** matching file parses successfully. A truncated tree is an error, not a misleading partial count.

## Files produced

- `repository_counts.csv`: one row for **each matching repository**, even if the branch is missing, the files are missing, or parsing fails; status `ok`, `no_tfvars`, or `error`. The `has_declarations` flag supports migration review but is **not** an exemption decision.
- `file_counts.csv`: one row per successfully parsed TFVARS file, with region/environment, branch and commit SHA.
- `environment_counts.csv`: aggregated by region/environment across successfully scanned repositories.
- `summary.json`: total counts and scan completeness statistics.
- `errors.csv`: explicit failures for migration audit.

## Counting rules

| Classification | Top-level Terraform variables |
|---|---|
| Resource declarations | `topics`, `schemas`, `pools`, `groups_mapping`, `services_accounts` |
| RBAC assignments | `pools_rbac`, `groups_mapping_rbac`, `services_accounts_rbac` |
| Metadata entries | `topics_meta_datas`, `schemas_meta_datas` |

Each object in a top-level list is **one declaration**. For example, three `topics` objects = three topic declarations; five `pools_rbac` objects = five role binding declarations. Do not add metadata to resource totals, and do not treat references to the same resource in different records as unique deployed assets. Other top-level list-of-object collections appear in the `unknown_collections` column for later support.

## Completeness and safety

- A missing target branch is reported as a repository scan error; **no silent fallback** to a different branch.
- A repository with no matching TFVARS is `no_tfvars`, rather than being mistaken for a repository containing zero resource declarations.
- A TFVARS parsing error fails that repository's counts rather than publishing a misleading partial inventory.
- The scanner reads file contents in memory but **never exports raw TFVARS values**, credentials, or identities. The output contains names, counts, paths, statuses, and provenance only.
- Git trees that are truncated by GitHub are marked as errors rather than silently treated as complete.
- Counts represent **GitHub declarations on the selected branch**, not resources deployed in Confluent Cloud and not confirmed governance exemption eligibility.
- The scanner uses the GitHub.com API configured in `gh`; if using GitHub Enterprise Server, ensure `gh` authentication/host targets the correct server (the script's endpoint host behavior should be adapted for that deployment).

## Phase 2 — configurable governance checks and topic prefix exploration

The previous repository and file counts remain unchanged. Configure governance checks in a JSON file with a `rules` array. Each rule needs `id`, `collection`, `field` (nested fields are supported, e.g. `config.retention.ms`), `operator`, and a `value` unless `operator=required`. Operators: `regex` (full-match), `not_regex` (search), `max`, `min`, `equals`, `one_of`, and `required`.

**IMPORTANT:** `rules.example.json` is illustrative, NOT your enterprise's actual governance policy. Supply an approved rules file for meaningful compliance results. With no `--rules`, reports explicitly show `not_evaluated` rather than implying compliance.

Example:

```bash
python scanner.py --org Desjardins --prefix ttfe-cc-rs- \
  --branch develop --rules rules.example.json --prefix-depth 3 --output ./inventory-output
```

Additional outputs:
- `migration_readiness.csv`: one row per repository with distinct noncompliant declaration count, total violations, topic count, prefix candidate count, and overlap indicators.
- `governance_violations.csv`: one row per rule failure, with resource name, rule, source file and field. Treat values as potentially sensitive; share cautiously.
- `topic_prefixes.csv`: per repository/region/environment prefix candidates at all depths. `cross_repository_overlap` means this prefix also matches a topic declared by another repository **within the same region/environment**, not that a governance ownership conflict necessarily exists.
- `topics.csv`: topic inventory used to derive prefix candidates.

A resource may violate several rules but is counted only once for `noncompliant_resources`. Prefixes are dot-delimited and end in a dot, so `sec.trace.` matches `sec.trace.foo` but not `sec.tracer.foo`. Prefix statistics are exploratory, with no automatic grants, policy exemptions or migrations. A prefix may cover more than one topic; prefix counts are not resource counts. Cross-repository analysis is confined to scanned repos, branch and matching files; it cannot certify exclusive ownership organization-wide. `topics_covered_by_nonoverlapping_prefixes` is a coverage indicator at the selected depth, not a minimal-prefix solution.

This code performs read-only GitHub API calls. Governance data derives from declarative tfvars only, not from real deployed state. Repositories that fail scanning are excluded from prefix analysis.
