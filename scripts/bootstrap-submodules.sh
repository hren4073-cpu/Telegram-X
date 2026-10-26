#!/usr/bin/env bash
# Keep every gitlink pinned, including nested dependencies with update=rebase defaults.
set -euo pipefail
cd "$(dirname "$0")/.."
expected=b47cd822037a4c38916f0b78f57298f10a88821e
actual=$(git rev-parse HEAD:tdlib)
[[ "$actual" == "$expected" ]] || { echo "Unexpected TDLib gitlink: $actual" >&2; exit 1; }
git submodule sync --recursive
# Hydrate binary dependencies in bootstrap-tdlib-lfs.py after pinned source checkout.
if ! GIT_LFS_SKIP_SMUDGE=1 git submodule update --init --recursive --checkout --jobs "${BOOTSTRAP_JOBS:-4}"; then
  echo 'Pinned checkout failed; fetching complete history for shallow submodules.' >&2
  git submodule foreach --recursive 'if test "$(git rev-parse --is-shallow-repository)" = true; then git fetch --unshallow origin; fi'
  GIT_LFS_SKIP_SMUDGE=1 git submodule update --init --recursive --checkout --jobs "${BOOTSTRAP_JOBS:-4}"
fi
[[ "$(git -C tdlib rev-parse HEAD)" == "$expected" ]]
status=$(git submodule status --recursive)
printf '%s\n' "$status"
if printf '%s\n' "$status" | rg '^[+U-]'; then
  echo 'Uninitialized, conflicted or mismatched submodules' >&2; exit 1
fi
