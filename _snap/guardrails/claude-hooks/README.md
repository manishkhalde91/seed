# Claude Code hooks for snap-guard

`hooks.json` wires `snap-guard` into Claude Code, so the slop rules are checked while the
agent works, not only at commit time. The edit-time hooks only warn: nothing is blocked except edits to the
guardrails' own files. The git hook blocks by default. The whole-change check runs in the build flows, pre-commit and CI.

## What each hook does

| Hook | When | What it does |
|---|---|---|
| `PreToolUse` | Before an edit or a shell command | **Denies** edits to `_snap/_config/guardrails.yaml`, to `.git/hooks/`, and removing the snap-guard hooks from `.claude/settings*.json`. **Asks the person** (yes or no) before `git ... --no-verify` and before installing a package that is not in the project's package files yet. Says nothing otherwise. |
| `PostToolUse` | After an edit | Checks only the lines the agent just changed and tells the agent what it found. It never blocks. |
| `SessionStart` | When a session starts | Says snap-guard is active. Prints one line if snap-guard is not installed. |

**Deny** has no prompt: only a person may change those files. **Ask** shows the person a
prompt with the reason. If the person says no, the command is cancelled and the agent is told.

## Safe when snap-guard is missing or old

Every command is `command -v snap-guard ... && snap-guard hook <event> || true`. A missing
snap-guard does nothing, and an old build that does not know `hook` (it would exit 2, which
Claude Code treats as a block) is turned into exit 0 by `|| true`. Every `snap-guard hook`
event also exits 0 on any error.

## For the installer: merge, do not replace

Write these hooks into the project's `.claude/settings.json` (a committed file):

1. Read the existing `.claude/settings.json` (or start from `{}`).
2. For each event in `hooks.json`, **append** Snap's entry to that event's array. Keep every
   existing hook. Skip an entry whose `command` is already there (re-running must not duplicate).
3. Keep all other settings as they are.
4. Check that the installed `snap-guard` supports `hook` (`snap-guard hook` with no event
   exits 0; an old build exits 2) before writing. If it does not, skip and say so.

## Windows

The guard (`command -v`, `&&`, `||`, `if ... fi`) needs a bash-like shell (Git Bash). It is
not tested on Windows. Other known Windows points are in `docs/guardrails-handoff.md`.
