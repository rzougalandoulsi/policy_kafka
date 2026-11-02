#!/usr/bin/env bash
# Workflow C/E step 3: opens (or updates) the pull request in the application repository that changes
# deploy/helm/governance/releases/<env>.json. Merging it triggers the application deploy, which rolls the pods and
# hands over to Workflow D. For dev the PR is set to auto-merge.
#   app-repo-pr.sh --repo ORG/policy_kafka --environment dev --release-id rel-xxx --file <rendered release json>
#                  [--kind promote|rollback] [--reason TEXT] [--base main] [--release-dir deploy/helm/governance/releases]
# Environment: GH_TOKEN with contents:write and pull_requests:write on the application repository [A].
set -euo pipefail
repo="" env="" release_id="" file="" kind=promote reason="" base="main" release_dir="deploy/helm/governance/releases"
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) repo="$2"; shift 2 ;;
    --environment) env="$2"; shift 2 ;;
    --release-id) release_id="$2"; shift 2 ;;
    --file) file="$2"; shift 2 ;;
    --kind) kind="$2"; shift 2 ;;
    --reason) reason="$2"; shift 2 ;;
    --base) base="$2"; shift 2 ;;
    --release-dir) release_dir="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$repo" ] && [ -n "$env" ] && [ -n "$release_id" ] && [ -f "$file" ] || { echo "usage: $0 --repo ORG/REPO --environment ENV --release-id ID --file JSON [--kind promote|rollback]" >&2; exit 2; }
: "${GH_TOKEN:?GH_TOKEN is required}"
branch="policy-release/$env/$release_id"
[ "$kind" = rollback ] && branch="policy-rollback/$env/$release_id"
work="$(mktemp -d)"
git clone --quiet --depth 1 --branch "$base" "https://x-access-token:${GH_TOKEN}@github.com/$repo.git" "$work/app"
cd "$work/app"
git config user.name "governance-policies[bot]"
git config user.email "governance-policies-bot@users.noreply.github.com"
git checkout -q -b "$branch"
mkdir -p "$release_dir"
cp "$file" "$release_dir/$env.json"
if git diff --quiet -- "$release_dir/$env.json"; then echo "$release_dir/$env.json already points at $release_id; nothing to open"; exit 0; fi
git add "$release_dir/$env.json"
title="$( [ "$kind" = rollback ] && echo "Roll back" || echo "Promote") policy release $release_id to $env"
git commit -q -m "$title" -m "Source: governance-policies Workflow $( [ "$kind" = rollback ] && echo E || echo C). $reason"
git push -q --force-with-lease origin "$branch"
body="$(printf '%s\n\n- environment: `%s`\n- release: `%s`\n- kind: `%s`\n- reason: %s\n\nMerging rolls the governance pods (new ConfigMap checksum); each pod is ready only when its OPA sidecar runs exactly these six revisions. Workflow D verifies the environment after the deploy.\n' "$title" "$env" "$release_id" "$kind" "${reason:-n/a}")"
existing="$(gh pr list -R "$repo" --head "$branch" --state open --json number --jq '.[0].number' 2>/dev/null || true)"
if [ -n "$existing" ]; then
  gh pr edit -R "$repo" "$existing" --title "$title" --body "$body" >/dev/null
  url="$(gh pr view -R "$repo" "$existing" --json url --jq .url)"
else
  url="$(gh pr create -R "$repo" --base "$base" --head "$branch" --title "$title" --body "$body" --label policy-release 2>/dev/null \
    || gh pr create -R "$repo" --base "$base" --head "$branch" --title "$title" --body "$body")"
fi
echo "pull request: $url"
if [ "$env" = dev ] && [ "$kind" = promote ]; then
  gh pr merge -R "$repo" --auto --squash "$url" && echo "auto-merge enabled (dev)" || echo "auto-merge could not be enabled (enable it on the repository or merge manually)"
fi
[ -n "${GITHUB_OUTPUT:-}" ] && echo "pull-request=$url" >> "$GITHUB_OUTPUT"
