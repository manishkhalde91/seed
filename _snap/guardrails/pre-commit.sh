#!/bin/sh
# Snap Guardrails — pre-commit hook.
#
# Runs the slop rules on the staged lines and shows what it finds. By default it BLOCKS:
# real findings stop the commit. It never stops work because snap-guard is not
# installed, the network is down, or a tool is missing.
#
# Mode (first one set wins):
#   1. SNAP_GUARD=off|warn|block            environment, for one commit or a shell
#   2. git config snap.guard off|warn|block  this repo; add --global for every repo
#   3. pre_commit: off|warn|block            in _snap/_config/guardrails.yaml (the team's choice)
#   4. block                                 default
#
# In block mode only real findings (exit 1) and a broken _snap/_config/guardrails.yaml (exit 2)
# stop the commit. Packages that could not be checked (exit 3, e.g. offline) still warn.
#
# On a clean pass it leaves a marker that prepare-commit-msg.sh turns into the trailer
#   Snap-Guard: pass <rule-pack-version>

root=$(git rev-parse --show-toplevel) || exit 0
cd "$root" || exit 0

marker=$(git rev-parse --git-path snap-guard-pass)
rm -f "$marker"

mode=${SNAP_GUARD:-}
[ -n "$mode" ] || mode=$(git config --get snap.guard 2>/dev/null)
# SNAP_HOME is the _snap folder, set by the git hook shim; it lives outside this repo in a
# multi-repo project. Without it, _snap is inside the repo.
guardrails_file=${SNAP_HOME:-_snap}/_config/guardrails.yaml
if [ -z "$mode" ] && [ -f "$guardrails_file" ]; then
  mode=$(sed -n 's/^pre_commit:[[:space:]]*\([a-z]*\).*/\1/p' "$guardrails_file" | head -n 1)
fi
case "$mode" in
  off)   exit 0 ;;
  warn)  ;;
  *)     mode=block ;;
esac

how_to_change="snap-guard: to change this, run 'git config snap.guard off|warn|block' (or set SNAP_GUARD for one commit)."

# snap-guard on this machine: the project's copy, else the one on PATH.
if [ -x node_modules/.bin/snap-guard ]; then
  guard=node_modules/.bin/snap-guard
elif command -v snap-guard > /dev/null 2>&1; then
  guard=snap-guard
else
  echo "snap-guard: not installed on this machine, so the code was not checked. Commit continues." >&2
  echo "  Install: npm install -g @snap/guardrails" >&2
  exit 0
fi

version=$("$guard" --version 2>/dev/null)
"$guard" check --staged
status=$?

case $status in
  0)
    # Tie the pass to exactly what is staged, so a later commit cannot reuse it.
    printf '%s %s\n' "$version" "$(git write-tree)" > "$marker"
    exit 0
    ;;
  1)
    if [ "$mode" = block ]; then
      echo "" >&2
      echo "snap-guard: commit stopped (block mode). Fix the BLOCK findings above, stage the fix and commit again." >&2
      echo "$how_to_change" >&2
      exit 1
    fi
    echo "" >&2
    echo "snap-guard: findings above (warn mode). The commit continues." >&2
    ;;
  2)
    if [ "$mode" = block ]; then
      echo "" >&2
      echo "snap-guard: commit stopped: _snap/_config/guardrails.yaml has an error (see above)." >&2
      echo "$how_to_change" >&2
      exit 2
    fi
    echo "" >&2
    echo "snap-guard: _snap/_config/guardrails.yaml has an error (see above), so the check did not run. The commit continues." >&2
    ;;
  3)
    echo "" >&2
    echo "snap-guard: some packages could not be checked (offline or registry down). The commit continues; a person should check them." >&2
    ;;
  *)
    echo "" >&2
    echo "snap-guard: the check could not finish (exit $status). The commit continues." >&2
    ;;
esac
echo "$how_to_change" >&2
exit 0
