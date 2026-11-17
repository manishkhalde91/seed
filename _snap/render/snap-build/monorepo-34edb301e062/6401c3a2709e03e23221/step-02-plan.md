# Step 2: Plan

## RULES

- **Language** — Speak in `English`. Write any file output in `English`.
- No intermediate approvals.

## INSTRUCTIONS

1. Draft resume check. If `{spec_file}` exists with `status: draft`, read it and capture the verbatim `<frozen-after-approval>...</frozen-after-approval>` block as `preserved_intent`. Otherwise `preserved_intent` is empty.
2. Investigate codebase. If step-01 set `lake_files`, the feature page among them is a set of **hypotheses about the code, not facts**: it says what the product did when it was last checked. Use it to know where to look, then check every claim you carry forward. A Code Map entry (path, symbol, line, behaviour) that came from the page is written only after you have opened the code and confirmed it; where the code disagrees, the code wins and you note the disagreement under Open Questions. If `lake_stale_count` is above 0 and a stale line bears on this change, name it there as well: its ID, and that the page may be out of date. A page line never replaces reading the code.
   Then investigate the codebase. _Isolate deep exploration in synchronous subagents/tasks where available. To prevent context snowballing, instruct subagents to give you distilled summaries only._ When the runtime lets you choose a model for these exploration subagents, run them on a small, fast model (e.g. Haiku) — they only read, search, and summarize; you do the synthesis. Decide which findings actually matter for execution — the specific files, symbols/lines, reuse points, and read-only constraints — and carry those forward for the Code Map. This is where the investigation lands: the spec preserves it so it is never re-narrated to the implementer at dispatch time.
3. Read `/Users/viral.chonkar/projects/monorepo/_snap/render/snap-build/monorepo-34edb301e062/6401c3a2709e03e23221/spec-template.md` fully. Fill it out based on the intent and investigation, resolving the template's `date` field to the current system date. Drain the investigation into the `## Code Map` section — annotated paths, symbol/line anchors, reuse pointers, and read-only evidence — so the spec is the implementer's investigation map and the step-03 handoff need only point at it. If `preserved_intent` is non-empty, replace the `<frozen-after-approval>` block in the spec you just filled out with `preserved_intent`, before writing. If step-01 set `lake_files`, list each in the spec's frontmatter `context:` as a `{project-root}/`-prefixed path (keep the list short: the resolver already stays inside its token budget). Write the result to `{spec_file}`.
   If step-01 resolved a feature (`feature` in the resolver JSON), set the spec frontmatter `feature:` to its slug; if the work is a feature the lake has no page for, set it to the slug the feature will have. Set `rules_touched:` to the lake rule IDs (`BR-`, `VAL-`, `ST-`) from that page that this change bends, changes or removes. Take them from the page, never invent one. These two fields are what step-04b writes back from.
4. Self-review against READY FOR DEVELOPMENT standard.
5. If intent gaps exist, do not fantasize, do not leave open questions, HALT and ask the human.
6. Token count check (see SCOPE STANDARD). If spec exceeds 1600 tokens:
   - Show user the token count. If this spec covers a deliberately multi-story work packet (see WORK PACKET STANDARD), note that a larger spec can be legitimate here and that [K] is reasonable when the packet is still one work packet a developer would develope.
   - HALT and ask human: `[S] Split — carve off secondary goals` | `[K] Keep full spec — accept the risks`
   - On **S**: Propose the split — name each secondary goal. For each deferred goal, append one new entry to `/Users/viral.chonkar/projects/monorepo/_snap-output/implementation-artifacts/deferred-work.md` using this format. Do not modify existing entries or look for duplicates. Rewrite the current spec to cover only the main goal — do not surgically carve sections out; regenerate the spec for the narrowed scope. Continue to checkpoint.
     ```markdown
     - source_spec: `{spec_file}`
       summary: <one sentence naming the deferred goal>
       evidence: <why this was split from the current spec>
     ```
   - On **K**: Continue to checkpoint with full spec.
7. Check the facts. For each item in the spec's `## Things this relies on`, confirm it exists: in the codebase, in the package registry, or in the library's docs. Record one outcome per item:
   - **confirmed** — found, and it does what the plan needs.
   - **not found** — you checked and it does not exist, or it cannot be done as written. This is an allowed outcome, not a failure; do not work around it by quietly choosing something else.
   - **could not check** — offline, registry unreachable, docs unavailable. This is a warning only; never report it as "does not exist".
   A client system named in the functional spec is an item too: confirm it is named there. Keep the outcomes for CHECKPOINT 1.
8. Tech spec critic. Write the user's original request or story text to a uniquely-named file in the system temp directory. Launch a context-free subagent with this prompt:

   > Read `{skill-root}/review-prompts/tech-spec-critic.md` completely and follow it as your review instructions.
   >
   > spec_file: `{spec_file}`
   > intent: the original request, at `<path of the temp file>`. Read that file.
   > conventions_file: `{project-root}/_snap-output/context/prd/product-conventions.md` — include this line only when that file exists; otherwise leave it out.
   > feature_page: the feature page among `lake_files`, if step-01 set one — include this line only then.
   >
   > Do not invoke any skill, and do not spawn subagents of your own — you are the reviewer. If the instruction file is unreadable, report that exact failure and stop. Return your findings as text in your final message; do not route them through any findings-reporting tool the host may offer.

   Keep the findings for CHECKPOINT 1. The critic only advises: its findings never block, and you do not change the spec because of them.

   Record the findings yourself, as the main agent (the critic subagent never writes to the spec). Append a `## Tech Spec Critic Findings` section at the end of `{spec_file}`, outside the `<frozen-after-approval>` block, with the findings exactly as returned, one per line. If the critic returned `No findings.`, write `No findings.` under the heading. When a later edit resolves a finding, put `[SOLVED]` at the start of that line and leave the rest of it unchanged. If step 8 runs again, replace the section with the new result and keep the `[SOLVED]` tag only on findings that are still listed and still resolved.

### CHECKPOINT 1

Present summary. Display the spec file path as a CWD-relative path (no leading `/`) so it is clickable in the terminal. If token count exceeded 1600 and user chose [K], include the token count and explain why it may be a problem.

Above the approval prompt, show:

- **Things this relies on** — every item that came back **not found** or **could not check**, with what you looked at. Omit when all are confirmed.
- **Tech spec critic** — its findings grouped by severity (high, medium, low), each with the spec line it concerns. Say plainly that the critic advises and the person decides. If it returned `No findings.`, say so in one line.

If any item is **not found**, say so first and wait for the person's decision; do not approve past it yourself.

After presenting the summary, display this note:

---

Before approving, you can open the spec file in an editor or ask me questions and tell me what to change. You can also use `snap-advanced-elicitation`, `snap-party-mode`, or `snap-code-review` skills, ideally in another session to avoid context bloat.

---

HALT and ask human: `[A] Approve` | `[E] Edit`

- **A**: Re-read `{spec_file}` from disk.
  - **If the file is missing:** HALT. Tell the user the spec file is gone and STOP — do not write anything to `{spec_file}`, do not set status, do not proceed to Step 3. Nothing below this point runs.
  - **If the file exists:** Compare the content to what you wrote. If it has changed since you wrote it, acknowledge the external edits — show a brief summary of what changed — and proceed with the updated version. Then set status `ready-for-dev` in `{spec_file}`. Everything inside `<frozen-after-approval>` is now locked — only the human can change it. → Step 3.
- **E**: Apply changes, then return to CHECKPOINT 1. Re-run step 7 for any changed item. When an edit resolves a recorded critic finding, tag it `[SOLVED]` in `## Tech Spec Critic Findings`. Re-run step 8 (the critic) only if the `<frozen-after-approval>` block or the `## Things this relies on` list changed.

## NEXT

Read fully and follow `/Users/viral.chonkar/projects/monorepo/_snap/render/snap-build/monorepo-34edb301e062/6401c3a2709e03e23221/step-03-implement.md`
