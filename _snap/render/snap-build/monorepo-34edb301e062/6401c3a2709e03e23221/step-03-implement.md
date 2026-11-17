---
---

# Step 3: Implement

## RULES

- **Language** — Speak in `English`. Write any file output in `English`.
- No push. No remote ops.
- Sequential execution only.
- Content inside `<frozen-after-approval>` in `{spec_file}` is read-only. Do not modify.

## PRECONDITION

Verify `{spec_file}` resolves to a non-empty path and the file exists on disk. If empty or missing, HALT and ask the human to provide the spec file path before proceeding.

## INSTRUCTIONS

### Baseline

Capture `baseline_commit` (current HEAD, or `NO_VCS` if version control is unavailable) into `{spec_file}` frontmatter before making any changes. If the frontmatter already contains `baseline_commit` (resumed run), preserve the existing value — never overwrite it.

### Implement

Change `{spec_file}` status to `in-progress` in the frontmatter before starting implementation.

If `{story_key}` is not empty and `/Users/viral.chonkar/projects/monorepo/_snap-output/implementation-artifacts/sprint-status.yaml` exists, read `/Users/viral.chonkar/projects/monorepo/_snap/render/snap-build/monorepo-34edb301e062/6401c3a2709e03e23221/sync-sprint-status.md` with `{target_status}` = `in-progress`.

Execute the implementation handoff below: substitute the runtime placeholders (e.g. `{spec_file}`) into it, then follow it verbatim.

Pick the model for the implementation subagent (only when the runtime lets you set one; otherwise ignore this and use the default):

- Read `{spec_file}`'s `## Tasks & Acceptance` and `<frozen-after-approval>` sections first.
- Use a small, fast model (e.g. Haiku) by default — the spec is a complete checklist and step-04 review with loopback is the safety net.
- Escalate to a model at the current session's capability (e.g. Sonnet) when ANY of these hold:
  - the change touches authentication/authorization, concurrency/async, data migrations, money/billing, or cryptography;
  - the `## Tasks & Acceptance` Execution list has more than 5 tasks;
  - the spec introduces a pattern the codebase does not already use (per the Code Map);
  - a prior review loopback already sent this spec back to implementation (`review_loop_iteration` >= 1 in frontmatter).
- When in doubt, escalate.

Launch a subagent with no prior conversation context, on the chosen model, with this prompt:

> Read {spec_file} fully and implement it — the spec is the sole source of truth. Load every file listed in its frontmatter `context:` before you start.
>
> Before reporting done, run `snap-guard check --diff <baseline_commit from the spec's frontmatter>` and fix every finding. If `snap-guard` is not installed, say "snap-guard: not run" and continue.
>
> When done, report what you changed, how you verified it, and anything left incomplete or risky.


Do not add goal restatements, file lists, ownership boundaries, investigation detail, acceptance criteria, or CLAUDE.md/house-style rules to the dispatch — the spec is the subagent's sole source of truth, and that material already lives in it (investigation findings in its Code Map, the rest in the spec body). One line of sanctioned hedging belongs in the spec at planning time, not in the dispatch. If no subagents are available, implement directly from the spec. If the platform allows, keep the subagent available for re-engagement after it returns — step-04 may send it review fixes.

The handoff directs the subagent to load the spec's `context:` files itself, so never pre-load and paste those files into the dispatch. Only when you implement directly (no subagent available) do you load a non-empty `context:` list yourself before starting.

**Path formatting rule:** Any markdown links written into `{spec_file}` must use paths relative to `{spec_file}`'s directory so they are clickable in VS Code. No leading `/`. Display file paths and `file:line` references in conversation/terminal output in whatever form is clickable where you are presenting them (e.g. code citation in chat, CWD-relative path with no leading `/` in terminal). If unsure, use CWD-relative path.

### Tasks & Acceptance Verification

Stage the diff and read it first: using the repository's version-control tooling, write a unified diff of all changes since `{baseline_commit}` (from `{spec_file}` frontmatter) — untracked files included — to a uniquely-named file in the system temp directory, set `{diff_file}` to its absolute path, and read that file into your own context. Judge against the diff, not against the implementation subagent's report.

Verify every task in the `## Tasks & Acceptance` section of `{spec_file}` is complete and every acceptance criterion is satisfied. Mark each finished task `[x]`. If any task is not done or any acceptance criterion is not satisfied, finish the missing work before proceeding — and when that changes code, rewrite `{diff_file}` and re-read it.

### Matrix Test Audit

If `{spec_file}`'s `<frozen-after-approval>` block contains an I/O & Edge-Case Matrix, verify every matrix row is covered by at least one test that verifies its expected behavior, and that each covering test ran and passed in the verification output. A covering test that exists but did not run — unregistered, filtered out, skipped, or disabled — counts as missing. If a test disagrees with the matrix, never edit the expectation to match the code: fix the code, or if the matrix row itself is ambiguous, HALT and ask the human. Fix any other audit failure before proceeding.

## NEXT

Read fully and follow `/Users/viral.chonkar/projects/monorepo/_snap/render/snap-build/monorepo-34edb301e062/6401c3a2709e03e23221/step-04-review.md`
