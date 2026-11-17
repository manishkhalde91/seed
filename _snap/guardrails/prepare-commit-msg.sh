#!/bin/sh
# Snap — prepare-commit-msg hook.
#
# Adds two kinds of trailers to the commit message. It never stops a commit.
#
# 1. Snap details (Snap-Spec, Snap-Story, Snap-Requirement, Snap-Agent, Snap-Model).
#    A Snap build saves them with _snap/scripts/snap_trailers.py just before it commits.
#    They are added only when the saved tree is the tree being committed, so a later manual
#    commit never gets them. A saved set that does not match can never match again, so it
#    is deleted. Merge and squash messages are left alone.
#
# 2. "Snap-Guard: pass <rule-pack-version>" when pre-commit.sh passed for exactly the tree
#    being committed. A commit made with --no-verify skips pre-commit, so it gets no
#    Snap-Guard trailer, and CI can tell the local gate did not run.
#
# Arguments (from git): $1 = commit message file, $2 = message source, $3 = commit sha.

msg_file=$1
source=$2

tree=$(git write-tree 2>/dev/null)

# --- 1. Snap details ---------------------------------------------------------------
saved=$(git rev-parse --git-path snap-trailers)
if [ -f "$saved" ]; then
  case "$source" in
    merge|squash) ;;
    *)
      first=
      stale=
      {
        read -r first
        if [ -n "$tree" ] && [ "$first" = "tree $tree" ]; then
          set -- --in-place --if-exists replace
          while IFS= read -r line; do
            [ -n "$line" ] && set -- "$@" --trailer "$line"
          done
          git interpret-trailers "$@" "$msg_file" 2>/dev/null
        else
          stale=1
        fi
      } < "$saved"
      [ -z "$stale" ] || rm -f "$saved"
      ;;
  esac
fi

# --- 2. Snap-Guard -----------------------------------------------------------------
marker=$(git rev-parse --git-path snap-guard-pass)
if [ -f "$marker" ]; then
  read -r version marked_tree < "$marker"
  rm -f "$marker"

  if [ -n "$version" ] && [ "$marked_tree" = "$tree" ]; then
    git interpret-trailers --in-place --if-exists replace \
      --trailer "Snap-Guard: pass $version" "$msg_file"
  fi
fi
exit 0
