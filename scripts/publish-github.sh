#!/usr/bin/env bash
# Developer-only helper for an existing, authorized repository. Never creates or force-pushes.
set -euo pipefail
cd "$(dirname "$0")/.."
repo="${1:-Readiz/android-tizen-wgt-installer}"
[[ "$repo" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Usage: ./scripts/publish-github.sh OWNER/REPOSITORY' >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit or review local changes first.' >&2; exit 1; }
branch="$(git branch --show-current)"
[[ -n "$branch" ]] || { echo 'Detached HEAD: choose a branch before publishing.' >&2; exit 1; }
url="https://github.com/$repo.git"
git ls-remote "$url" >/dev/null
echo "Publishing $branch to $repo without force. Git credential authentication is required."
git push "$url" "HEAD:refs/heads/$branch"
