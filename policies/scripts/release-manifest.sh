#!/usr/bin/env bash
# Writes a release manifest (contracts/release-manifest.schema.json) for the six bundles.
#   release-manifest.sh --artifacts DIR --out FILE [--previous FILE] [--base-url URL] [--source candidate|local]
#                       [--git-sha SHA] [--git-ref REF]
# A bundle freshly built in DIR (<name>-<revision>.tar.gz) gets a new entry; a bundle absent from DIR keeps the entry
# of --previous (the last candidate manifest). The release id is rel-<first 12 hex of sha256(sorted "name=revision"
# joined by ",")>, exactly what Governance's PolicyRelease.of computes, so the same set has the same id everywhere.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
artifacts="" out="" previous="" base_url="" source="candidate" git_sha="" git_ref=""
while [ $# -gt 0 ]; do
  case "$1" in
    --artifacts) artifacts="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --previous) previous="$2"; shift 2 ;;
    --base-url) base_url="$2"; shift 2 ;;
    --source) source="$2"; shift 2 ;;
    --git-sha) git_sha="$2"; shift 2 ;;
    --git-ref) git_ref="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$artifacts" ] && [ -n "$out" ] || { echo "usage: $0 --artifacts DIR --out FILE [--previous FILE] [--base-url URL]" >&2; exit 2; }
git_sha="${git_sha:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
git_ref="${git_ref:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
python3 - "$artifacts" "$out" "$previous" "$base_url" "$source" "$git_sha" "$git_ref" "$(tr -d '[:space:]' < OPA_VERSION)" <<'PY'
import datetime, glob, hashlib, json, os, re, sys, tarfile
artifacts, out, previous, base_url, source, git_sha, git_ref, opa_version = sys.argv[1:9]
names = ["core", "manifests", "naming", "quotas", "resources", "changes"]
prev = json.load(open(previous)) if previous else {"bundles": {}}
bundles, built, kept = {}, [], []
for n in names:
    files = [f for f in glob.glob(os.path.join(artifacts, f"{n}-*.tar.gz")) if not f.endswith(".sha256")]
    if len(files) > 1:
        sys.exit(f"{n}: more than one artifact in {artifacts}: {files}")
    if files:
        f = files[0]
        revision = os.path.basename(f)[:-len(".tar.gz")]
        with tarfile.open(f) as tar:
            def plain(name):
                while name.startswith("/") or name.startswith("./"):
                    name = name[1:] if name.startswith("/") else name[2:]
                return name
            member = next(m for m in tar.getmembers() if plain(m.name) == ".manifest")
            manifest = json.load(tar.extractfile(member))
            signed = any(plain(m.name) == ".signatures.json" for m in tar.getmembers())
        if manifest.get("revision") != revision:
            sys.exit(f"{n}: file name says revision {revision} but .manifest says {manifest.get('revision')}")
        meta = manifest.get("metadata", {})
        entry = {"revision": revision, "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest(), "signed": signed}
        if base_url:
            entry["url"] = f"{base_url.rstrip('/')}/{n}/{revision}/{revision}.tar.gz"
        else:
            entry["file"] = os.path.basename(f)
        for key, src in (("contractVersion", "contract_version"), ("builtAt", "built_at"), ("gitSha", "git_sha")):
            if src in meta:
                entry[key] = meta[src]
        bundles[n] = entry
        built.append(n)
    elif n in prev.get("bundles", {}):
        bundles[n] = prev["bundles"][n]
        kept.append(n)
    else:
        sys.exit(f"{n}: no artifact in {artifacts} and no previous manifest entry; build it or pass --previous")
joined = ",".join(f"{n}={bundles[n]['revision']}" for n in sorted(bundles))
release_id = "rel-" + hashlib.sha256(joined.encode()).hexdigest()[:12]
contract = bundles["core"].get("contractVersion") or prev.get("contractVersion")
manifest = {
    "releaseId": release_id,
    "createdAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "gitSha": git_sha,
    "gitRef": git_ref,
    "contractVersion": contract,
    "opaVersion": opa_version,
    "source": source,
    "previousReleaseId": prev.get("releaseId"),
    "bundles": {n: bundles[n] for n in names},
}
if prev.get("releaseId") == release_id:
    sys.exit(f"release {release_id} is identical to the previous candidate: nothing new to publish")
os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
with open(out, "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
print(f"release {release_id}: built {built or 'none'}, kept {kept or 'none'} -> {out}")
PY
