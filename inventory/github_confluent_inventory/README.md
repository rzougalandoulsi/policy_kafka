# GitHub Confluent Cloud Inventory Scanner

Scans `<region>/<environment>/input.tfvars` in each GitHub repository and exports the declarations found in supported Terraform variables. Nothing is modified in GitHub or Confluent Cloud.

## Prerequisites
- Python 3.9+
- GitHub CLI (`gh`) with access to the private repositories
- `python -m pip install -r requirements.txt`
- `gh auth login` (or a correctly configured `GH_TOKEN`)

## Run

```bash
python scanner.py --repos repos.txt --branch develop --output ./inventory-output
```

Omit `--branch` to use each repository's default branch. Or specify `--org Desjardins` and place just repository names in the list. Change `--pattern` to a regular expression matching the full file path for other layouts.

## Output
- `inventory.json`: complete extracted declarations, with provenance and redacted sensitive fields
- `all.csv`: flat inventory with original declaration JSON in a column
- One CSV per supported Terraform variable (`topics.csv`, `pools_rbac.csv`, etc.)
- `summary.json`: counts by type
- `errors.json`: repositories that failed scanning

CSV rows include repository, branch, commit SHA, region, environment, file path, category, index, name, principal, role, CRN type, CRN parameters and declaration JSON.

## Important behavior
- Uses the GitHub Git Trees API and Blob API; no clone needed.
- Scans only matching `input.tfvars`, not `.avsc` file contents.
- Does not evaluate dynamic Terraform expressions, read external variable files, or resolve Terraform references.
- Detects truncated Git trees and reports errors rather than silently claiming a complete scan.
- `topics_meta_datas` and `schemas_meta_datas` remain separate declarations; automatic joins are not performed.
- Fields with names indicating secrets, credentials, tokens, passwords or keys are redacted in outputs.
- Inventory reflects *declared* resources in Git, not the actual deployed Confluent Cloud state.
