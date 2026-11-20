#!/usr/bin/env python3
"""Read Confluent Cloud resources declared in GitHub input.tfvars files.

Requires `gh auth login` and `pip install python-hcl2`.
Does not clone repositories or apply Terraform.
"""
import argparse
import base64
import csv
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import quote

import hcl2

KNOWN = (
    "topics", "topics_meta_datas", "schemas", "schemas_meta_datas", "pools",
    "pools_rbac", "groups_mapping", "groups_mapping_rbac",
    "services_accounts", "services_accounts_rbac",
)
SENSITIVE = re.compile(r"(?:password|secret|token|private[_-]?key|api[_-]?key|credential)", re.I)
HEADERS = ["repository", "branch", "commit", "region", "environment", "file_path", "resource_type", "resource_index", "resource_name", "principal", "role_name", "crn_type", "crn_params", "resource_json"]


def gh_json(endpoint):
    command = ["gh", "api", "--header", "Accept: application/vnd.github+json", endpoint]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError(f"GitHub API request failed for {endpoint}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def scrub(value):
    if isinstance(value, dict):
        return {k: "[REDACTED]" if SENSITIVE.search(k) else scrub(v) for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    return value


def read_repos(path, default_org):
    result = []
    for line in path.read_text(encoding="utf-8").splitlines():
        name = line.strip()
        if not name or name.startswith("#"):
            continue
        name = re.sub(r"^https://github\.com/", "", name).removesuffix(".git").strip("/")
        if "/" not in name:
            if not default_org:
                raise ValueError(f"Repository '{name}' must use owner/repo or --org")
            name = f"{default_org}/{name}"
        if name.count("/") != 1:
            raise ValueError(f"Invalid repository: {name}")
        if name not in result:
            result.append(name)
    return result


def resource_name(kind, item):
    for field in ("topic_name", "subject_name", "pool_name", "group_mapping_display_name", "display_name", "name"):
        if item.get(field) is not None:
            return str(item[field])
    return ""


def extract_rows(repository, branch, commit, filepath, parsed):
    parts = filepath.split("/")
    region, environment = parts[0], parts[1]
    rows = []
    for kind in KNOWN:
        items = parsed.get(kind, [])
        if not isinstance(items, list):
            raise ValueError(f"{filepath}: '{kind}' must be a list (got {type(items).__name__})")
        for index, original in enumerate(items):
            if not isinstance(original, dict):
                raise ValueError(f"{filepath}: '{kind}[{index}]' must be an object")
            item = scrub(original)
            principal = (item.get("pool_name") or item.get("group_mapping_display_name")
                         or item.get("sa_display_name") or "") if kind.endswith("_rbac") else ""
            rows.append({
                "repository": repository, "branch": branch, "commit": commit,
                "region": region, "environment": environment, "file_path": filepath,
                "resource_type": kind, "resource_index": index,
                "resource_name": resource_name(kind, item), "principal": principal,
                "role_name": item.get("role_name", ""), "crn_type": item.get("crn_type", ""),
                "crn_params": json.dumps(item.get("crn_params", {}), ensure_ascii=False, sort_keys=True),
                "resource_json": json.dumps(item, ensure_ascii=False, sort_keys=True),
                "attributes": item,
            })
    return rows


def scan(repository, selected_branch, pattern):
    owner, repo = repository.split("/")
    base = f"repos/{quote(owner, safe='')}/{quote(repo, safe='')}"
    metadata = gh_json(base)
    branch = selected_branch or metadata["default_branch"]
    ref = gh_json(f"{base}/git/ref/heads/{quote(branch, safe='/')}")
    commit = ref["object"]["sha"]
    # Resolve annotated tags is unnecessary: this is a branch ref.
    tree = gh_json(f"{base}/git/trees/{commit}?recursive=1")
    if tree.get("truncated"):
        raise RuntimeError(f"GitHub tree truncated for {repository}; refusing incomplete inventory")
    files = [t for t in tree.get("tree", []) if t.get("type") == "blob" and re.fullmatch(pattern, t["path"])]
    records = []
    for file in files:
        path = file["path"]
        blob = gh_json(f"{base}/git/blobs/{file['sha']}")
        if blob.get("encoding") != "base64":
            raise ValueError(f"Unsupported blob encoding for {repository}/{path}")
        contents = base64.b64decode(blob["content"]).decode("utf-8-sig")
        import io
        parsed = hcl2.load(io.StringIO(contents))
        records.extend(extract_rows(repository, branch, commit, path, parsed))
    return records, len(files)


def write_outputs(rows, output):
    output.mkdir(parents=True, exist_ok=True)
    with (output / "inventory.json").open("w", encoding="utf-8") as fh:
        json.dump(rows, fh, ensure_ascii=False, indent=2)
    for kind in ("all", *KNOWN):
        subset = rows if kind == "all" else [r for r in rows if r["resource_type"] == kind]
        with (output / f"{kind}.csv").open("w", encoding="utf-8-sig", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=HEADERS, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(subset)
    counts = Counter(r["resource_type"] for r in rows)
    with (output / "summary.json").open("w", encoding="utf-8") as fh:
        json.dump({"total": len(rows), "by_type": dict(counts), "repositories": sorted({r['repository'] for r in rows})}, fh, indent=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repos", type=Path, required=True, help="Text file: owner/repo on each line")
    parser.add_argument("--org", help="Organization to prepend to short repo names")
    parser.add_argument("--branch", help="Use same branch in all repos; otherwise each default branch")
    parser.add_argument("--pattern", default=r"[^/]+/[^/]+/input\.tfvars", help="Full-match regular expression for input.tfvars paths")
    parser.add_argument("--output", type=Path, default=Path("inventory-output"))
    args = parser.parse_args()
    repositories = read_repos(args.repos, args.org)
    all_rows, errors = [], []
    for repository in repositories:
        try:
            rows, count = scan(repository, args.branch, args.pattern)
            all_rows.extend(rows)
            print(f"OK {repository}: {count} files, {len(rows)} declarations", file=sys.stderr)
        except (RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
            errors.append({"repository": repository, "error": str(exc)})
            print(f"ERROR {repository}: {exc}", file=sys.stderr)
    write_outputs(all_rows, args.output)
    (args.output / "errors.json").write_text(json.dumps(errors, indent=2), encoding="utf-8")
    print(f"Wrote {len(all_rows)} declarations to {args.output}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
