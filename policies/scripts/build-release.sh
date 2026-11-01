#!/usr/bin/env bash
# Builds signed, optimized, immutable bundle artifacts for Artifactory (Workflow B step 2 and 3).
#   build-release.sh --suffix <yyyyMMdd>.<run>-<short sha> --signing-key FILE [--bundles "a b"|all]
#                    [--out DIR] [--git-sha SHA] [--git-ref REF] [--no-optimize]
# For every bundle: a staging copy with .manifest {roots, rego_version, revision "<bundle>-<suffix>",
# metadata{git_sha, git_ref, built_at, contract_version, opa_version, bundle}}, then
#   opa build -O1 -e <entrypoints> --partial-namespace governance/<bundle>/partial -r <revision>
#             --signing-key KEY --signing-alg RS256 --claims-file <claims> -o DIR/<bundle>-<revision>.tar.gz
# plus DIR/<bundle>-<revision>.tar.gz.sha256 and the extracted DIR/<bundle>-<revision>.signatures.json.
# The partial namespace keeps the optimizer's output under the bundle's own root; without it every optimized bundle
# would add a shared root "partial" and OPA would refuse to activate the set (overlapping roots).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OPA=scripts/opa.sh
ALL="core manifests naming quotas resources changes"
suffix="" key="" bundles="$ALL" out="dist/release" git_sha="" git_ref="" optimize=1
while [ $# -gt 0 ]; do
  case "$1" in
    --suffix) suffix="$2"; shift 2 ;;
    --signing-key) key="$2"; shift 2 ;;
    --bundles) bundles="${2//,/ }"; [ "$bundles" = all ] && bundles="$ALL"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --git-sha) git_sha="$2"; shift 2 ;;
    --git-ref) git_ref="$2"; shift 2 ;;
    --no-optimize) optimize=0; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$suffix" ] && [ -n "$key" ] || { echo "usage: $0 --suffix <yyyyMMdd>.<run>-<sha> --signing-key FILE [--bundles ...]" >&2; exit 2; }
[ -f "$key" ] || { echo "signing key $key not found" >&2; exit 1; }
case "$suffix" in [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].*-*) ;; *) echo "suffix must look like <yyyyMMdd>.<run>-<short sha>, got '$suffix'" >&2; exit 1 ;; esac
git_sha="${git_sha:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
git_ref="${git_ref:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
opa_version="$(tr -d '[:space:]' < OPA_VERSION)"
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# In Docker mode the key lives outside the mounted repository: mount it read-only at a fixed path.
key_arg="$key"
if [ "$($OPA --mode)" = docker ]; then
  export OPA_DOCKER_ARGS="${OPA_DOCKER_ARGS:-} -v $(cd "$(dirname "$key")" && pwd)/$(basename "$key"):/signing/key.pem:ro"
  key_arg=/signing/key.pem
fi
mkdir -p "$out" dist
stage_root="$(mktemp -d dist/stage.XXXXXX)"
sha256() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | awk '{print $1}'; }
for b in $bundles; do
  case " $ALL " in *" $b "*) ;; *) echo "unknown bundle $b" >&2; exit 1 ;; esac
  revision="${b}-${suffix}"
  stage="$stage_root/$b"
  mkdir -p "$stage"
  cp -R "bundles/$b/." "$stage/"
  contract="$(grep -h '^contract_version := "' "bundles/$b/"*.rego | head -1 | sed 's/.*"\(.*\)".*/\1/')"
  [ -n "$contract" ] || { echo "$b: contract_version not declared" >&2; exit 1; }
  python3 - "$stage/.manifest" "$b" "$revision" "$git_sha" "$git_ref" "$built_at" "$contract" "$opa_version" <<'PY'
import json, sys
p, b, rev, sha, ref, at, contract, opa = sys.argv[1:9]
m = json.load(open(p))
m["revision"] = rev
m["metadata"] = {"bundle": b, "git_sha": sha, "git_ref": ref, "built_at": at, "contract_version": contract, "opa_version": opa}
json.dump(m, open(p, "w"))
PY
  claims="$stage_root/$b.claims.json"
  printf '{"keyid":"governance-policies","bundle":"%s","revision":"%s","git_sha":"%s","built_at":"%s"}\n' "$b" "$revision" "$git_sha" "$built_at" > "$claims"
  opt=()
  if [ $optimize -eq 1 ]; then
    if [ "$b" = core ]; then opt=(-O1 -e governance/core/decision -e governance/core/ready -e governance/core/policy_catalog)
    else opt=(-O1 -e "governance/$b/violations" -e "governance/$b/approvals" -e "governance/$b/catalog" -e "governance/$b/contract_version"); fi
    opt+=(--partial-namespace "governance/$b/partial")
  fi
  target="$out/$revision.tar.gz"
  [ ! -e "$target" ] || { echo "$target already exists; refusing to rebuild a revision" >&2; exit 1; }
  echo ">> build $b -> $target"
  $OPA build -b "$stage" "${opt[@]}" -r "$revision" --ignore '*_test.rego' --signing-key "$key_arg" --signing-alg RS256 --claims-file "$claims" -o "$target"
  digest="$(sha256 "$target")"
  printf '%s  %s\n' "$digest" "$(basename "$target")" > "$target.sha256"
  tar -xzf "$target" -O /.signatures.json 2>/dev/null > "$out/$revision.signatures.json" || tar -xzf "$target" -O ./.signatures.json 2>/dev/null > "$out/$revision.signatures.json"
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out/$revision.signatures.json"
  echo "   $revision sha256=$digest contract=$contract signed=yes optimized=$optimize"
done
echo "built: $bundles -> $out (staging in $stage_root)"
