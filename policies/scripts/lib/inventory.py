#!/usr/bin/env python3
"""Collects the rules and bundle inventory of governance-policies and renders it into one self-contained HTML page.

Called by scripts/inventory.sh from the repository root. Sources, in order of authority:
  - the merged catalogue data.governance.core.policy_catalog (opa eval over the six bundles): one entry per policy id;
  - bundles/<name>/.manifest and *.rego: roots, packages, contract_version, the file and line of every
    h.violation("ID", ...) / h.approval("ID", ...) rule and what that rule reads (configuration keys, team and usage
    fields, manifest and action fields), including one level of module-level helpers the rule body references;
  - tests/*_test.rego: test functions whose body names the policy id, mapped to a bundle through their imports;
  - tests/fixtures/expected/*.json: fixtures whose decision matched the policy id;
  - deploy/helm/governance/releases/<env>.json (when present): the revision each environment pins per bundle;
  - .github/CODEOWNERS: owners per bundle directory.
The JSON embedded in the page is {summary, bundles[], policies[], fixtures[], releases{}} with sorted keys and no
timestamps or git data, so regenerating from the same sources produces the same bytes (inventory.sh --check).
"""
import glob
import json
import os
import re
import subprocess
import sys

BUNDLES = ["core", "manifests", "naming", "quotas", "resources", "changes"]
RULE_RE = re.compile(r'h\.(violation|approval)\("([A-Z0-9_]+)"')
DEF_RE = re.compile(r"^([a-z_][a-z0-9_]*)\b")

READS = [
    (re.compile(r'object\.get\(h\.config\.(\w+),\s*"(\w+)"'), lambda m: f"configuration.{m.group(1)}.{m.group(2)}"),
    (re.compile(r"h\.config\.(\w+)\.(\w+)"), lambda m: f"configuration.{m.group(1)}.{m.group(2)}"),
    (re.compile(r'h\.limit\("(\w+)"\)'), lambda m: f"configuration.limits.{m.group(1)} (environmentOverrides apply)"),
    (re.compile(r'object\.get\(input\.team,\s*"(\w+)"'), lambda m: f"team.{m.group(1)}"),
    (re.compile(r"input\.team\.(\w+)"), lambda m: f"team.{m.group(1)}"),
    (re.compile(r"h\.team_prefixes|h\.has_authorized_prefix"), lambda m: "team.authorizedPrefixes"),
    (re.compile(r"h\.projected|h\.usage_value"), lambda m: "usage.current, usage.reserved, usage.delta"),
    (re.compile(r"input\.ownership"), lambda m: "ownership.resources"),
    (re.compile(r"input\.repository\.(\w+)"), lambda m: f"repository.{m.group(1)}"),
    (re.compile(r"h\.bindings"), lambda m: "manifest.rbac, manifest.identityPools[].rbac"),
    (re.compile(r"input\.manifest\.(\w+)"), lambda m: f"manifest.{m.group(1)}"),
    (re.compile(r"input\.actions"), lambda m: "actions[]"),
    (re.compile(r"input\.plan\.(\w+)"), lambda m: f"plan.{m.group(1)}"),
    (re.compile(r"input\.exceptions"), lambda m: "exceptions[]"),
    (re.compile(r"h\.env\b"), lambda m: "environment"),
]


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def catalogue(opa):
    args = [opa, "eval"]
    for b in BUNDLES:
        args += ["-b", f"bundles/{b}"]
    args += ["--format=json", "data.governance.core.policy_catalog"]
    out = subprocess.run(args, capture_output=True, text=True, check=True).stdout
    return json.loads(out)["result"][0]["expressions"][0]["value"]


def blocks(lines):
    """Yields (start_line_index, text) for every top-level block: from a non-indented line to the next line that is
    exactly '}' or to the next blank line for one-line definitions."""
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line or line.startswith(("#", "\t", " ", "package", "import")):
            i += 1
            continue
        j = i
        if line.rstrip().endswith("{"):
            while j < len(lines) and lines[j] != "}":
                j += 1
        yield i, "\n".join(lines[i : j + 1])
        i = j + 1


def reads_of(text):
    found = set()
    for rx, fmt in READS:
        for m in rx.finditer(text):
            found.add(fmt(m))
    return found


def rego_rules(bundle):
    """Returns {policy_id: [{file, line, reads}]} for one bundle and the module helpers referenced."""
    rules = {}
    for path in sorted(glob.glob(f"bundles/{bundle}/*.rego")):
        lines = read(path).split("\n")
        helpers = {}
        for start, text in blocks(lines):
            m = DEF_RE.match(text)
            if m and m.group(1) not in ("violations", "approvals", "catalog", "contract_version", "default"):
                helpers.setdefault(m.group(1), []).append(text)
        for start, text in blocks(lines):
            m = RULE_RE.search(text)
            if not m:
                continue
            reads = reads_of(text)
            for name, bodies in helpers.items():
                if re.search(rf"\b{re.escape(name)}\b", text):
                    for body in bodies:
                        reads |= reads_of(body)
            rules.setdefault(m.group(2), []).append(
                {"file": os.path.relpath(path).replace(os.sep, "/"), "line": start + 1, "kind": m.group(1), "reads": sorted(reads)}
            )
    return rules


def bundle_info(bundle, owners):
    manifest = json.loads(read(f"bundles/{bundle}/.manifest"))
    files = sorted(os.path.basename(p) for p in glob.glob(f"bundles/{bundle}/*.rego"))
    packages = set()
    contract = None
    for f in files:
        text = read(f"bundles/{bundle}/{f}")
        pm = re.search(r"^package\s+(\S+)", text, re.M)
        if pm:
            packages.add(pm.group(1))
        cm = re.search(r'^contract_version := "([^"]+)"', text, re.M)
        if cm:
            contract = cm.group(1)
    return {
        "name": bundle,
        "roots": manifest.get("roots", []),
        "regoVersion": manifest.get("rego_version"),
        "contractVersion": contract,
        "packages": sorted(packages),
        "files": files,
        "owners": owners.get(bundle, []),
    }


def codeowners():
    owners = {}
    path = ".github/CODEOWNERS"
    if not os.path.isfile(path):
        return owners
    for line in read(path).splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        m = re.match(r"^/bundles/(\w+)/$", parts[0])
        if m:
            owners[m.group(1)] = parts[1:]
    return owners


def tests():
    """Returns ([{file, package, bundle, tests[]}], {policy_id: [{file, test}]})."""
    files, by_policy = [], {}
    for path in sorted(glob.glob("tests/*_test.rego")):
        text = read(path)
        pkg = re.search(r"^package\s+(\S+)", text, re.M)
        if not pkg or pkg.group(1) == "governance.fixtures":
            continue
        bundle = None
        for imp in re.findall(r"^import data\.governance\.(\w+)", text, re.M):
            if imp in BUNDLES:
                bundle = imp
                break
        names = []
        lines = text.split("\n")
        for start, block in blocks(lines):
            tm = re.match(r"^(test_\w+)", block)
            if not tm:
                continue
            names.append(tm.group(1))
            for pid in set(re.findall(r'"([A-Z][A-Z0-9_]{5,})"', block)):
                by_policy.setdefault(pid, []).append({"file": path.replace(os.sep, "/"), "test": tm.group(1)})
        files.append({"file": path.replace(os.sep, "/"), "package": pkg.group(1), "bundle": bundle, "tests": names})
    return files, by_policy


def fixtures():
    out = []
    for path in sorted(glob.glob("tests/fixtures/expected/*.json")):
        name = os.path.basename(path)[: -len(".json")]
        expected = json.loads(read(path))
        stage = None
        inp = f"tests/fixtures/inputs/{name}.json"
        if os.path.isfile(inp):
            stage = json.loads(read(inp)).get("stage")
        out.append({"name": name, "stage": stage, "decision": expected.get("decision"), "matchedPolicies": sorted(expected.get("matchedPolicies", []))})
    return out


def releases(directory):
    out = {}
    if not directory or not os.path.isdir(directory):
        return out
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        env = os.path.basename(path)[: -len(".json")]
        doc = json.loads(read(path))
        out[env] = {
            "releaseId": doc.get("releaseId"),
            "contractVersion": doc.get("contractVersion"),
            "bundles": {n: {k: v for k, v in b.items() if k in ("revision", "keyId")} for n, b in doc.get("bundles", {}).items()},
        }
    return out


def build(opa, release_dir):
    owners = codeowners()
    cat = catalogue(opa)
    rules = {}
    for b in BUNDLES:
        for pid, locs in rego_rules(b).items():
            rules.setdefault(pid, []).extend(locs)
    test_files, tests_by_policy = tests()
    fx = fixtures()
    policies = []
    for entry in sorted(cat, key=lambda e: (BUNDLES.index(e["bundle"]), e["policyId"])):
        pid = entry["policyId"]
        locs = rules.get(pid, [])
        reads = sorted({r for loc in locs for r in loc["reads"]})
        policies.append(
            {
                **entry,
                "stages": sorted(entry.get("stages", []), key=["REQUEST", "ACTION_PLAN"].index),
                "locations": [{k: v for k, v in loc.items() if k != "reads"} for loc in locs],
                "reads": reads,
                "tests": sorted(tests_by_policy.get(pid, []), key=lambda t: (t["file"], t["test"])),
                "fixtures": [f["name"] for f in fx if pid in f["matchedPolicies"]],
                "exemptable": bool(entry.get("bypassable")),
                "shadowable": True,
            }
        )
    emitted_not_catalogued = sorted(set(rules) - {p["policyId"] for p in policies})
    bundles = []
    for b in BUNDLES:
        info = bundle_info(b, owners)
        info["policies"] = [p["policyId"] for p in policies if p["bundle"] == b]
        info["testFiles"] = [t["file"] for t in test_files if t["bundle"] == b]
        info["testCount"] = sum(len(t["tests"]) for t in test_files if t["bundle"] == b)
        bundles.append(info)
    summary = {
        "policies": len(policies),
        "bypassable": sum(1 for p in policies if p["bypassable"]),
        "hardDenies": sum(1 for p in policies if p["severity"] == "HARD_DENY"),
        "approvals": sum(1 for p in policies if p["kind"] == "APPROVAL"),
        "bundles": len(bundles),
        "tests": sum(len(t["tests"]) for t in test_files),
        "fixtures": len(fx),
        "withoutLocation": sorted(p["policyId"] for p in policies if not p["locations"]),
        "emittedNotCatalogued": emitted_not_catalogued,
    }
    return {"summary": summary, "bundles": bundles, "policies": policies, "fixtures": fx, "releases": releases(release_dir), "testFiles": test_files}


def render(data, template_path):
    template = read(template_path)
    payload = json.dumps(data, indent=1, sort_keys=True, ensure_ascii=False).replace("</", "<\\/")
    marker = "__INVENTORY_JSON__"
    if template.count(marker) != 1:
        sys.exit(f"{template_path}: expected exactly one {marker} marker")
    return template.replace(marker, payload)


def main():
    opa, template_path, release_dir, out, check = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] == "check"
    data = build(opa, release_dir)
    html = render(data, template_path)
    s = data["summary"]
    note = f"{s['policies']} policies ({s['bypassable']} bypassable, {s['hardDenies']} hard deny, {s['approvals']} approvals), {s['bundles']} bundles, {s['tests']} tests, {s['fixtures']} fixtures"
    if s["withoutLocation"]:
        sys.exit(f"inventory: catalogued policies without a rule in the sources: {s['withoutLocation']}")
    if s["emittedNotCatalogued"]:
        sys.exit(f"inventory: rules without a catalogue entry: {s['emittedNotCatalogued']}")
    if check:
        if not os.path.isfile(out):
            sys.exit(f"inventory: {out} does not exist; run scripts/inventory.sh")
        if read(out) != html:
            sys.exit(f"inventory: {out} is out of date ({note}); run scripts/inventory.sh and commit the result")
        print(f"inventory up to date: {out} ({note})")
        return
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(html)
    print(f"inventory written: {out} ({note})")


if __name__ == "__main__":
    main()
