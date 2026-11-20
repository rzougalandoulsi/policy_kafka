#!/usr/bin/env python3
"""Read-only GitHub scanner: discover prefix-matching repos and count TFVARS declarations.

Phase 1 of a Confluent Cloud self-service / governance migration inventory.
"""

from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import re
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

import governance

try:
    import hcl2
except ImportError:
    sys.exit("Missing dependency: install with 'python -m pip install -r requirements.txt'")

# The names intentionally match the Terraform variables in the supplied input.tfvars.
RESOURCE_TYPES = (
    "topics", "schemas", "pools", "groups_mapping", "services_accounts",
)
RBAC_TYPES = (
    "pools_rbac", "groups_mapping_rbac", "services_accounts_rbac",
)
METADATA_TYPES = (
    "topics_meta_datas", "schemas_meta_datas",
)
KNOWN = (*RESOURCE_TYPES, *RBAC_TYPES, *METADATA_TYPES)
COMMON_COLUMNS = ["repository", "branch", "commit_sha"]
COUNT_COLUMNS = [*KNOWN, "resource_total", "rbac_total", "metadata_total"]
REPO_COLUMNS = [
    *COMMON_COLUMNS, "status", "archived", "github_url", "tfvars_files",
    "regions", "environments", "has_declarations", *COUNT_COLUMNS, "unknown_collections", "message",
]
FILE_COLUMNS = [
    *COMMON_COLUMNS, "file_path", "region", "environment", "status",
    *COUNT_COLUMNS, "unknown_collections", "message",
]
ENV_COLUMNS = ["region", "environment", "repository_count", "tfvars_files", *COUNT_COLUMNS]


def gh_get(path: str, max_attempts: int = 3) -> dict | list:
    """Use the currently authenticated GitHub CLI session; never print tokens."""
    command = ["gh", "api", "--method", "GET", "--header", "Accept: application/vnd.github+json", path]
    for attempt in range(max_attempts):
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode == 0:
            try:
                return json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Invalid JSON from GitHub for {path}: {exc}") from exc
        error_text = (result.stderr or result.stdout).strip()
        # Avoid retries on authorization/missing branch, but retry transient network/server responses.
        if any(marker in error_text.lower() for marker in ("http 401", "http 403", "http 404", "http 422")):
            raise RuntimeError(f"GitHub request failed at {path}: {error_text}")
        if attempt + 1 < max_attempts:
            time.sleep(2 ** attempt)
        else:
            raise RuntimeError(f"GitHub request failed at {path}: {error_text}")
    raise AssertionError("unreachable")


def discover_repositories(org: str, prefix: str) -> list[dict]:
    """Page through the entire org; filter locally to avoid GitHub code/search indexing."""
    matched = []
    page = 1
    while True:
        data = gh_get(f"/orgs/{quote(org, safe='')}/repos?type=all&per_page=100&page={page}")
        if not isinstance(data, list):
            raise RuntimeError("Unexpected response while listing organization repositories")
        matched.extend(repo for repo in data if str(repo.get("name", "")).startswith(prefix))
        print(f"Discovery page {page}: {len(data)} repos, {len(matched)} matching", file=sys.stderr)
        if len(data) < 100:
            break
        page += 1
    return sorted(matched, key=lambda r: r["name"].casefold())


def count_items(parsed: dict, file_path: str) -> tuple[dict[str, int], dict[str, int]]:
    """Count list objects, not the values contained inside each object."""
    counts = Counter({name: 0 for name in KNOWN})
    for kind in KNOWN:
        items = parsed.get(kind, [])
        if not isinstance(items, list):
            raise ValueError(f"{file_path}: '{kind}' must be a list, got {type(items).__name__}")
        if not all(isinstance(item, dict) for item in items):
            raise ValueError(f"{file_path}: '{kind}' contains a non-object item")
        counts[kind] = len(items)
    counts["resource_total"] = sum(counts[name] for name in RESOURCE_TYPES)
    counts["rbac_total"] = sum(counts[name] for name in RBAC_TYPES)
    counts["metadata_total"] = sum(counts[name] for name in METADATA_TYPES)
    # Preserve count-only evidence for other manifest types to extend the scanner later.
    unknown = {
        key: len(value)
        for key, value in parsed.items()
        if key not in KNOWN and isinstance(value, list) and all(isinstance(v, dict) for v in value)
    }
    return dict(counts), unknown


def empty_counts() -> dict[str, int]:
    return {name: 0 for name in COUNT_COLUMNS}


def scan_repository(org: str, info: dict, branch_arg: str, path_re: re.Pattern, rules=None, detail=None) -> tuple[dict, list[dict]]:
    rules = rules or []
    repo = info["name"]
    branch = info.get("default_branch") if branch_arg == "default" else branch_arg
    base = f"/repos/{quote(org, safe='')}/{quote(repo, safe='')}"
    report = {
        **dict.fromkeys(COMMON_COLUMNS, ""),
        "repository": repo, "branch": branch, "status": "ok",
        "archived": info.get("archived", False), "github_url": info.get("html_url", ""),
        "tfvars_files": 0, "regions": "", "environments": "", "has_declarations": False,
        **empty_counts(), "unknown_collections": "{}", "message": "",
    }
    # Use commit SHA for traceability, and its tree SHA to keep all reads consistent.
    commit = gh_get(f"{base}/commits/{quote(branch, safe='')}")
    sha = commit["sha"]
    tree_sha = commit["commit"]["tree"]["sha"]
    report["commit_sha"] = sha
    tree = gh_get(f"{base}/git/trees/{tree_sha}?recursive=1")
    if tree.get("truncated"):
        raise RuntimeError("GitHub tree was truncated: cannot certify complete counts")
    tfvars_files = sorted(
        (
            item for item in tree.get("tree", [])
            if item.get("type") == "blob" and path_re.fullmatch(item.get("path", ""))
        ),
        key=lambda item: item["path"],
    )
    report["tfvars_files"] = len(tfvars_files)
    if not tfvars_files:
        report["status"] = "no_tfvars"
        report["message"] = "No matching input.tfvars found on selected branch"
        return report, []

    file_rows = []
    aggregate = Counter()
    unknown_total = Counter()
    regions = set()
    environments = set()
    for item in tfvars_files:
        file_path = item["path"]
        blob = gh_get(f"{base}/git/blobs/{item['sha']}")
        if blob.get("encoding") != "base64":
            raise ValueError(f"{file_path}: unsupported GitHub blob encoding")
        raw = base64.b64decode(blob["content"], validate=False).decode("utf-8-sig")
        try:
            parsed = hcl2.load(io.StringIO(raw))
        except Exception as exc:
            raise ValueError(f"{file_path}: invalid HCL syntax (details omitted to avoid leaking config values)") from exc
        counts, unknown = count_items(parsed, file_path)
        context = {"repository":repo,"branch":branch,"commit_sha":sha,"file_path":file_path,
                   "region":file_path.split("/")[0],"environment":file_path.split("/")[1]}
        if detail is not None:
            detail['violations'].extend(governance.evaluate(parsed, rules, context))
            detail['topics'].extend(governance.topic_records(parsed, context))
        parts = file_path.split("/")
        region, environment = parts[0], parts[1]
        regions.add(region)
        environments.add(f"{region}/{environment}")
        aggregate.update(counts)
        unknown_total.update(unknown)
        file_rows.append({
            "repository": repo, "branch": branch, "commit_sha": sha,
            "file_path": file_path, "region": region, "environment": environment,
            "status": "ok", **counts, "unknown_collections": json.dumps(unknown, sort_keys=True),
            "message": "",
        })
    report.update({
        **{name: aggregate[name] for name in COUNT_COLUMNS},
        "regions": ";".join(sorted(regions)),
        "environments": ";".join(sorted(environments)),
        "has_declarations": bool(sum(aggregate[name] for name in COUNT_COLUMNS) or sum(unknown_total.values())),
        "unknown_collections": json.dumps(dict(unknown_total), sort_keys=True),
    })
    return report, file_rows


def write_csv(path: Path, columns: list[str], rows: list[dict]):
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_outputs(out: Path, repo_rows: list[dict], file_rows: list[dict], errors: list[dict], org: str, prefix: str):
    out.mkdir(parents=True, exist_ok=True)
    write_csv(out / "repository_counts.csv", REPO_COLUMNS, repo_rows)
    write_csv(out / "file_counts.csv", FILE_COLUMNS, file_rows)
    write_csv(out / "errors.csv", ["repository", "stage", "message"], errors)
    env_aggregate = {}
    for row in file_rows:
        key = (row["region"], row["environment"])
        rec = env_aggregate.setdefault(key, {
            "region": key[0], "environment": key[1], "repos": set(), "tfvars_files": 0,
            **empty_counts(),
        })
        rec["repos"].add(row["repository"])
        rec["tfvars_files"] += 1
        for name in COUNT_COLUMNS:
            rec[name] += row[name]
    env_rows = []
    for _, rec in sorted(env_aggregate.items()):
        repos = rec.pop("repos")
        rec["repository_count"] = len(repos)
        env_rows.append(rec)
    write_csv(out / "environment_counts.csv", ENV_COLUMNS, env_rows)
    total = Counter()
    for repo in repo_rows:
        if repo["status"] == "ok":
            total.update({name: repo[name] for name in COUNT_COLUMNS})
    summary = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "organization": org, "prefix": prefix,
        "repositories_matched": len(repo_rows),
        "repositories_scanned": sum(row["status"] == "ok" for row in repo_rows),
        "repositories_without_tfvars": sum(row["status"] == "no_tfvars" for row in repo_rows),
        "repositories_failed": sum(row["status"] == "error" for row in repo_rows),
        "tfvars_files_parsed": len(file_rows),
        "counts": {name: total[name] for name in COUNT_COLUMNS},
        "caveat": "Counts reflect Git declarations, not deployed Confluent Cloud resources or exemption eligibility.",
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--org", required=True, help="GitHub organization, e.g. Desjardins")
    parser.add_argument("--prefix", required=True, help="Repository name prefix (case-sensitive)")
    parser.add_argument("--branch", default="develop", help="Branch to scan, or 'default' for each repo's default branch")
    parser.add_argument("--pattern", default=r"[^/]+/[^/]+/input\.tfvars", help="Full-match regex for target paths")
    parser.add_argument("--output", type=Path, default=Path("inventory-output"))
    parser.add_argument("--rules", type=Path, help="JSON governance rule configuration (no rules applied if omitted)")
    parser.add_argument("--prefix-depth", type=int, default=3, help="Preferred topic prefix depth for summary, default 3")
    parser.add_argument("--prefix-delimiter", default=".", help="Topic prefix delimiter, default '.'")
    args = parser.parse_args()
    try:
        pattern = re.compile(args.pattern)
        rules = governance.load_rules(args.rules)
        if args.prefix_depth < 1 or not args.prefix_delimiter:
            raise ValueError("--prefix-depth must be >= 1 and delimiter must not be empty")
        repositories = discover_repositories(args.org, args.prefix)
    except (OSError, RuntimeError, re.error, ValueError, json.JSONDecodeError) as exc:
        print(f"FATAL: discovery failed: {exc}", file=sys.stderr)
        return 2
    print(f"Found {len(repositories)} repositories with prefix '{args.prefix}'", file=sys.stderr)
    repo_rows, file_rows, errors = [], [], []
    all_violations, all_topics = [], []
    for index, info in enumerate(repositories, 1):
        repo = info["name"]
        print(f"[{index}/{len(repositories)}] {repo}", file=sys.stderr)
        try:
            detail = {'violations':[], 'topics':[]}
            report, rows = scan_repository(args.org, info, args.branch, pattern, rules, detail)
            # Publish staged details only after the full repository scan succeeded.
            all_violations.extend(detail['violations'])
            all_topics.extend(detail['topics'])
            repo_rows.append(report)
            file_rows.extend(rows)
            print(f"  {report['status']}: {report['tfvars_files']} file(s), "
                  f"{report['resource_total']} resources, {report['rbac_total']} RBACs", file=sys.stderr)
        except (ValueError, RuntimeError, OSError, KeyError, TypeError) as exc:
            # Never include partially parsed files from a failed repository.
            message = str(exc)
            repo_rows.append({
                **dict.fromkeys(COMMON_COLUMNS, ""), "repository": repo,
                "branch": info.get("default_branch") if args.branch == "default" else args.branch,
                "status": "error", "archived": info.get("archived", False),
                "github_url": info.get("html_url", ""), "tfvars_files": 0,
                "regions": "", "environments": "", "has_declarations": False, **empty_counts(),
                "unknown_collections": "{}", "message": message,
            })
            errors.append({"repository": repo, "stage": "scan", "message": message})
            print(f"  ERROR: {message}", file=sys.stderr)
    prefixes = governance.prefix_inventory(all_topics,args.prefix_depth,args.prefix_delimiter)
    for row in repo_rows:
        matching = [v for v in all_violations if v['repository']==row['repository']]
        row['noncompliant_resources'] = governance.distinct_resources(matching)
        row['rule_violations'] = len(matching)
        row['governance_status'] = ('not_evaluated' if not rules else
                                    ('scan_failed' if row['status']=='error' else
                                     'violations' if matching else 'no_violations'))
        row.update(governance.repository_prefix_stats(row['repository'],all_topics,prefixes,args.prefix_depth))
    write_outputs(args.output, repo_rows, file_rows, errors, args.org, args.prefix)
    # Separate detailed evidence from the original Phase-1 count files.
    extra_cols = ['noncompliant_resources','rule_violations','governance_status','distinct_topics',
                  'prefix_candidates_at_depth','nonoverlapping_prefixes_at_depth',
                  'overlapping_prefixes_at_depth','topics_covered_by_nonoverlapping_prefixes']
    write_csv(args.output / 'migration_readiness.csv', REPO_COLUMNS+extra_cols, repo_rows)
    write_csv(args.output / 'governance_violations.csv',
              ['repository','branch','commit_sha','file_path','region','environment',
               'collection','item_index','resource_name','rule_id','field','operator',
               'actual_value','expected','reason'],all_violations)
    write_csv(args.output / 'topic_prefixes.csv',
              ['repository','region','environment','depth','prefix','topic_count',
               'cross_repository_overlap','other_repositories','preferred_depth'],prefixes)
    write_csv(args.output / 'topics.csv',
              ['repository','branch','commit_sha','file_path','region','environment','topic_name','topic_index'],all_topics)
    summary_path = args.output / 'summary.json'
    summary = json.loads(summary_path.read_text(encoding='utf-8'))
    summary.update({'governance_rules_loaded':len(rules), 'governance_rule_ids':[r['id'] for r in rules],
                    'rule_violations':len(all_violations),
                    'noncompliant_resource_declarations':governance.distinct_resources(all_violations),
                    'topic_prefix_depth':args.prefix_depth,
                    'note':'Prefix candidates are exploratory and are NOT authorizations. Governance results require explicitly configured rules.'})
    summary_path.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"Wrote {len(repo_rows)} repository rows, {len(file_rows)} file rows to {args.output}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
