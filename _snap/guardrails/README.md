# Snap Guardrails

Snap's slop rules (the `@snap/guardrails` package and its `snap-guard` command) check code
for common AI mistakes: copied code, swallowed errors, tests that check nothing, made-up
packages, leftover TODOs and more.

**Blocking is the default.** Real findings stop a commit; a team can change this to `warn` or
`off`. Nothing here changes a project's build, its CI or its package files. A check that cannot run (tool not
installed, no network) is skipped and says so.

## Who needs what

| | What to do | Effect |
|---|---|---|
| The project | Nothing extra: `snap install` puts this folder in `_snap/` like the skills | Every team member gets these files with the repo |
| Each developer | The installer sets up the checker on the machine by default, without asking. To do it by hand: `npm install -g @snap/guardrails@$(sed -n 's/^guardrails-version: *//p' _snap/_config/manifest.yaml)` | Checks run on that machine. Without it, they are skipped |
| The team (optional) | Add a CI job from [docs/ci.md](docs/ci.md) | A check on merge requests that nobody can skip |

`snap-guard doctor` shows which extra tools (security scanner, size checks, Python and Go
dead-code checks) are installed, with the install command for each one that is missing.

## Files

| File | What it does |
|---|---|
| `pre-commit.sh` | Git pre-commit hook: checks the staged lines and shows findings. Blocks by default |
| `prepare-commit-msg.sh` | Adds `Snap-Guard: pass <version>` to the commit message when the check passed. Also adds the Snap lines (`Snap-Intent`, `Snap-Agent`, `Snap-Model`) that a Snap build saved with `_snap/scripts/snap_trailers.py`, only when the saved tree is the tree being committed |
| `guardrails.yaml.example` | Example `_snap/_config/guardrails.yaml`: team-wide hook mode and rule adjustments, each with a reason |
| `claude-hooks/hooks.json` | Claude Code hooks (edit and session checks). The installer merges them into `.claude/settings.json`; see its README |
| `docs/ci.md` | Copy-paste CI jobs for GitLab, GitHub and others (optional) |
| `docs/hardening.md` | Optional package-manager safety settings, with what each one can break |

## The git hook

The hook is set up per developer, in that developer's own `.git/hooks`. It is never
committed, so colleagues who do not use Snap are not affected. A teammate who never ran the
installer gets no hook and no commit lines.

### Where the hook finds `_snap`

The installer saves `git config --local snap.home <path of the _snap folder>` in each repo.
The hook shim reads `snap.home` first, then falls back to `<repo>/_snap`. It passes `SNAP_HOME`
(the `_snap` folder) to the hook scripts. `pre-commit.sh` reads the team config from
`$SNAP_HOME/_config/guardrails.yaml`. Git shares `snap.home` between all worktrees of a repo, so
worktrees work too.

Two recommended layouts:

| Layout | Where Snap lives | Hook setup |
|---|---|---|
| Single repo | In the repo root: `my-app/_snap` | The installer sets up the repo where it runs |
| Multi repo | In its own repo next to the code repos: `parent/my-app-snap/_snap`, `parent/frontend`, `parent/backend` | Run the installer in the Snap repo. Add the code repos with `--repo <folder>` (repeatable) or the prompt |

Each developer runs `snap guard-setup`. Run it again after an update, so old hook shims are
upgraded.

| Mode | What happens on commit |
|---|---|
| `block` (default) | Blocking findings, or a broken `_snap/_config/guardrails.yaml`, stop the commit |
| `warn` | Shows findings; the commit goes through |
| `off` | Nothing runs |

Choose a mode (the first one set wins):

1. `SNAP_GUARD=off git commit …` — one commit
2. `git config snap.guard warn` — this repository, for you (`--global`: every repository)
3. `pre_commit: warn` in `_snap/_config/guardrails.yaml` — the team's default for the project

Even in `block` mode, a commit is never stopped because `snap-guard` is not installed or
because packages could not be checked while offline.

### Commit lines

A commit made through a Snap build can end with these lines:

```
Snap-Intent: spec=refunds tech-spec=partial-refunds story=AS-1423
Snap-Agent: snap-build@3.3.1
Snap-Model: claude-opus-5-5
Snap-Guard: pass 1.0.1
```

- `Snap-Intent` holds `name=value` pairs. Only the known ones, in this order: `spec`,
  `tech-spec`, `story`, `requirement`, `prompt` (a short slug of a free-text request).
- `Snap-Agent` is the skill and the Snap version. `Snap-Model` is the model id, or
  `no-model-found`. `Snap-Guard` is the check result.
- The build runs `_snap/scripts/snap_trailers.py set` after `git add` and right before
  `git commit`. It saves the details inside `.git` (never committed) with the tree hash of the
  staged files. The hook adds the lines only when the commit has exactly those staged files.
  A manual commit, or a commit with other changes, gets no Snap lines. A saved set that no
  longer matches is deleted.
- `snap-spec` and `snap-build` stage the files and wait. You run `git commit`.
  `snap-build-auto` commits by itself.
- Stage first, then commit. If other files are staged, or you use `git commit -a` or
  `git commit <file>`, the lines can be skipped.
- `git commit --amend --no-edit` keeps the lines. `--amend -m "new"` replaces the message, so
  the lines are lost. Merge and squash messages are not changed.
- **Privacy:** the `prompt` value is a short title. Never put client names or secrets in it. A
  commit message stays in git history.
- Multi-repo project: the build stages and saves the lines in each repo it changed, and the
  final message lists the `git -C <repo> commit` commands.
- Parallel builds: use separate git worktrees. Two builds in one folder share files and the
  staging area.

If `_snap` is outside the code repo, `snap-guard` needs `@snap/guardrails` 1.0.1 or later
to find `guardrails.yaml`.
