---
type: Guideline
title: "Test Design Rules Guideline"
description: "Team rules for snap-test-design: excluded test levels, coverage checklist, additional-test flags, automation readiness, and Resume, Edit and Validate additions"
tags: [guideline, test-design, test-levels, coverage]
timestamp: "2026-10-30"
snap:
  consumers: [snap-test-design]
  loaded_by: _snap/custom/snap-test-design.toml
  applies_to: "every run (sections 2 to 5; section 4.4 and section 6 are epic-level only)"
---

# snap-test-design

**How to read this file.** Run the skill's steps as written. This file only adds to them, step by step, and section 1 lists the main places where a team rule replaces skill text. "Step N §M" means section M of the skill's `steps-c/step-0N-*.md`; "edit step N" means `steps-e/step-0N-*.md`; "checklist" means the skill's `checklist.md`; "template" means the template the run uses (epic level: `test-design-template.md`; system level: `test-design-qa-template.md` and `test-design-architecture-template.md`). Where this file and the skill, its templates, checklist or knowledge fragments disagree, this file wins, whether or not section 1 lists the place.

**Scope.** Sections 2 to 5 apply to every run, except section 4.4 and the Flags and `additional-test` parts of section 5, which apply to epic-level runs only. Section 6, and every bullet or table row in sections 7 to 9 marked *(epic-level)*, apply to epic-level runs only, because step 6 runs only there.

## 0. Everywhere

- **Secrets.** Never copy a secret, key, credential, token or resource name (such as a bucket) from code, config or context docs into any output or into chat. Name its environment variable or config key instead. In Test Data, use credential placeholders: `<validToken>` or any placeholder that names a credential (`<adminToken>`, `<password>`).
- **Ask, don't guess.** Ask the user when a code location is unclear (section 8.2) and once at the end of step 4 for flagged cases (section 4.4). Everywhere else apply the stated default and list the assumption in the completion report, so an unattended run never stops on a borderline call.
- **User requests win.** If the user explicitly asks in this run for a level or case these rules exclude, plan it and add a second line to the Test levels note (section 4.1): `**Requested by user in run:** <Requirement> (<level>)`, one entry per row, separated by `;`. Rows on that line are **exempt rows**: they are exempt from the level check (section 5), and they count in plan totals, but step 6 never expands a `Unit` or `Component` row into CSV cases (no Scenario ID, no block, not in the block count); it lists them as skipped.

## 1. Where team rules replace the skill

| Skill text | Team rule |
| --- | --- |
| Step 4 §1 "Select test level (E2E / API / Component / Unit)", `test-levels-framework.md`, checklist "Test levels selected (E2E/API/Component/Unit)", "Unit tests for edge cases and algorithms", "Component tests for UI interactions" and "test-levels-framework.md referenced" (step 6 already skips `Unit` and `Component` rows itself) | `Unit` and `Component` are excluded (section 2); everything else the skill offers stays. Ignore every unit and component target, example and preference. |
| Step 4 §1 "Give every material risk at least one coverage row whose Test Level is suitable", template "Every material risk must appear in at least one coverage row" | A risk no allowed level can exercise is recorded as "uncovered" or "partial" (section 2), not given a row. |
| Step 4 §5 "Coverage target ≥ 80%", checklist "Coverage targets specified (≥80% recommended)", template "Coverage Targets" (critical paths, security scenarios, business logic, edge cases) | The template's targets stay (critical paths ≥80%, security 100%, business logic ≥70%, edge cases ≥50%). Each is the share of the matching acceptance criteria or rules that have at least one coverage row: critical paths = P0 criteria; business logic = rule IDs; edge cases = criteria covered by checklist items 3, 4, 7 and 8; security = security criteria. Section 4.3 already produces enough rows to meet them; add none to hit a number. Code-coverage gates and "coverage report" evidence are "N/A: no unit tests by team policy". |
| QA template checklist "Test tables with columns: Test ID \| Requirement \| Test Level \| Risk Link \| Notes" | Those columns plus `AC / rule` and `Technique` (section 4.2). The Test ID is `{priority}-{code}-{nn}` with code `API`, `INT` or `E2E` (for example `P0-API-001`). The QA template's tables have no Test Count or Owner column; add none. |
| Step 6 §3 Labels, checklist "no invented extra label", step 6 §4 "Priority and Labels match the scenario" | `additional-test` may be appended last and is not a contradiction (section 6.1). |
| Step 6 §5 progress table (Scenario ID, Case, Name) | Five columns: add Gaps and Status (section 6.2). |
| Template sections | Add these where the run's plan is written (epic level: `test-design-template.md`; system level: the QA document). **Test levels note** and **Coverage checklist table:** inside "Test Coverage Plan", the note directly after the priority note ("P0/P1/P2/P3 = priority, NOT execution timing"), the table directly under the last coverage table. **`## Clarifications`** and, when any case is flagged (epic level only), **`## Flags: additional-test scenarios`:** as their own sections directly after "Test Coverage Plan" (section 5). |

## 2. Test levels (create steps 4 to 6, Resume, Edit and Validate)

**Excluded:** `Unit` and `Component`. Every other level the skill offers (`API`, `Integration`, `E2E`) stays available with no extra condition; this file adds no gate on them, and an `API` row needs no story that mentions an endpoint.

- An `Integration` row must exercise a real data store, queue or external-service stand-in. In-process code with mocked dependencies is a unit test and is excluded.
- Where no spec item names an `API` row's route, fields or status codes, keep the row and leave `<fieldName>`/`<value>`/`<expectedValue>`/`<method>`/`<apiPath>` placeholders; never invent them. Section 6.2 tags them: route, method, path, status code and response values are `CONTRACT`; request-side inputs are `DATA`. Spec basis is judged per case under section 4.4.
- Endpoint calls for setup, seeding and teardown in an E2E or Integration case do not make it an API test. The step 6 Platform tag is not a Test Level.

**Re-leveling.** Move behavior the framework would put at unit or component level to the first level that can exercise it: `API`, then `Integration`, then `E2E`. One row at the new level; no compensating rows at other levels. The Test levels note (section 4.1) is the only place that states the exclusion; do not repeat it per table. Unit tests the repository already has, or that a story requires developers to write, may be cited as evidence in row Notes; they are never plan rows.

**When no allowed level works** (the level cannot trigger the risk, such as a race no allowed level can reproduce): do not invent screens or flows. Write "uncovered: <level> excluded by test-level policy" (or "partial: …") in the risk's Mitigation or QA Test Coverage cell and in the step 4 section of the progress file, and add it to "Not in Scope" with reason and mitigation.

## 3. Steps 1 to 3 add

- **Steps 1 and 2.** Step 2 also loads the spec bundle (section 4.4) and the context lake under `_snap-output/context/` (including `blast-radius-map.json`), because sections 4.3 item 9 and 4.4 depend on them. Missing either is not a blocker: say so in step 2's section of the progress file.
- **Clarification IDs.** Number every clarification `CL-nn`, starting at `CL-01` for each run (`{run_key}`). A resumed or re-run step continues that run's sequence; never reuse or renumber an ID, including one whose row was deleted.
- **Clarifications table.** The canonical table is `## Clarifications` in the plan (columns: ID, Decision the spec needs, Source, Scenarios); the progress file's step 4 section mirrors it. Step 3 creates the rows with Scenarios empty; step 4 fills Scenarios once coverage rows exist. Source is the risk or NFR it came from (for example `R-03`), the spec item that leaves it open, or `additional-test` (section 4.4).
- One row per open decision. A case that depends on several decisions appears in each of their rows.

## 4. Step 4 adds

### 4.1 Test levels note

Start the coverage plan (inside "Test Coverage Plan", directly after the priority note) with one line:

```
**Test levels:** Unit and Component excluded by team policy.
```

When the user asked for an excluded level (section 0), add the `**Requested by user in run:**` line directly below it.

### 4.2 Techniques and traceability in the coverage matrix

Add two columns to every coverage table: `AC / rule` (the acceptance criterion or business rule IDs the row covers) and `Technique` (the number and name of the checklist item below that produced it, for example `3 Boundary values`). Give each distinct behavior one row, ranked by risk. Boundary and partition variants of one behavior share its row and raise its Test Count; add a row only for a distinct behavior. A flagged row with no AC cites its checklist item and clarification instead (`7 Error handling / CL-07`); never invent an AC link.

Coverage rows have no ID before step 6 (which assigns `TS-nn`). Until then, cite a row by its Requirement text; when two rows share a Requirement, reword one so each is unique.

### 4.3 Coverage checklist

Apply each item to every acceptance criterion, field and business rule in the input.

| # | Item | Enumerate |
| --- | --- | --- |
| 1 | Happy paths | Every valid flow, including alternate valid roles and entry points. |
| 2 | Negative paths | Wrong types, missing mandatory fields, unauthorized roles, invalid transitions, unsupported files, expired sessions or tokens. |
| 3 | Boundary values | For every numeric, length, date or quantity rule: min, min-1, max, max+1, zero, negative, empty/null. |
| 4 | Equivalence partitions | One case per valid and per invalid partition of every input. |
| 5 | Decision tables | Every significant combination of a rule with 2+ conditions. |
| 6 | State transitions | Valid and invalid changes, illegal jumps, re-triggering from a terminal state. |
| 7 | Error handling | API timeout, 4xx and 5xx, concurrent edits by different users and races, session expiry mid-flow, double-click or duplicate submission. |
| 7a | Refresh or back/forward mid-flow | Does data persist, get lost silently, or submit twice? |
| 7b | Same user in two tabs or devices | Same or conflicting action on both: stale-data error, silent overwrite, or crash? |
| 7c | Role or access revoked mid-session | Is the next action allowed, silently blocked, or rejected with a proper permission error? |
| 7d | Network drop or timeout after submit | False success or failure, duplicate on retry, or stuck loading? |
| 8 | UI/UX edge cases | Special characters, emojis, very long strings, pasted formatted text, autofill, empty and loading states, pagination edges. |
| 9 | Cross-feature regression | Every module or flow sharing data, UI or flows with this feature, named explicitly, from the context lake (including `blast-radius-map.json`), the repo, and the Atlassian graph when connected. |
| 10 | Non-functional touchpoints | Where evident: load, accessibility, localization, currency. |
| 10a | Date and time boundaries | User-versus-server timezone, daylight saving, 11:59 PM vs 12:00 AM. Never assume one timezone. |

Under the coverage tables, add a **Coverage checklist** table with one row per item above and three columns: `#`, `Applied to` (every AC or rule ID the item was applied to), and `Covered by` (the coverage rows' Requirement text, per section 4.2; `N/A: <reason>` for the whole item or for each AC it does not apply to). Never leave an item blank.

### 4.4 Spec basis (epic-level)

**Spec bundle:** `SPEC.md`/`spec.md` beside the epic or story file and its companion files, the epic and its stories' acceptance criteria, and the documents step 2 loaded. Decide per case, when the scenario is created. A **case** is one of a row's Test Count; in step 4 it is a condition the row names. A case is an **additional test** (it has no spec basis) when no item in the spec bundle (success criterion, constraint, assumption, open question, non-goal, story AC or edge-case row, companion rule or contract) decides its expected outcome. Not flagged:

- A **general-rule basis**: a spec rule covers the case though it does not name the exact input (for example a price of 0 when the spec names only `price: 1`). This is the default.
- An **open question** already in the spec: cite its clarification.
- A **non-goal**: out of scope, do not plan it.
- **Regression or NFR touchpoints**: cite the context source.
- **User requests in this run**: cite the request.

Items 7, 7a to 7d and 8 are the usual candidates; apply the test to each. With no spec bundle, the epic's and stories' acceptance criteria are the basis. With no acceptance criteria at all, flag nothing and say the basis could not be judged.

For each flagged case: name it in the scenario's Notes with its clarification (for example "Cases double-click and two tabs are `additional-test` (CL-07)"), and add or extend a clarification row listing it. A flagged case is part of its row's Test Count, never extra. Step 6 places flagged cases after the row's other cases, in the order Notes names them, and matches each block to its Notes entry by that name. One row per open decision, listing every dependent scenario and case; reuse an existing row that already covers the decision and let it keep its Source. A row created for a flagged case has Source `additional-test`. An **`additional-test` clarification** is any row that lists a flagged case.

At the end of step 4, report the flagged count and clarifications, and ask whether to keep, demote or drop each flagged case. Dropping or demoting happens here or in Edit on the coverage matrix (Priority or Test Count, plus the clarification's Scenarios), never only in the CSV. A clarification left listing no case is deleted. After an Edit drop or demote, apply it in place (section 8.1); do not re-run step 6.

## 5. Step 5 adds

- Write the `## Clarifications` table (section 3) into the plan, in the place section 1 gives.
- *(epic-level)* `## Flags: additional-test scenarios`: each flagged scenario with its count of flagged cases.
- Remove or reword every mention of unit or component tests, including team roles, execution strategy, appendices and test IDs. Keep these policy statements: the Test levels note, the "Requested by user in run" line, "uncovered: <level> excluded by test-level policy" entries, "N/A: no unit tests by team policy", and evidence citations section 2 allows. Effort and count totals include only planned rows.
- Checklist pass (here and in Validate): an item asking for Unit or Component coverage, or for `test-levels-framework.md`, is met when section 2 excludes that level and the Test levels note says why. Never add rows to satisfy it.
- **Level check.** No row uses `Unit` or `Component` except exempt rows (section 0), and every coverage-checklist item is covered or N/A. Run it before saving here; Resume and Validate run the same check.

## 6. Step 6 adds (epic-level)

The CSV columns and step format are defined in the skill's step 6 (§3 and "Step Format"). Section 6 starts at 6.1.

### 6.1 Flagged cases

For each case flagged in section 4.4: Labels end with `additional-test` after the Platform tag (`{Test Level},{Risk IDs},{Platform tag},additional-test`, omitting Risk IDs as usual), on the first row only; the Objective cites the clarification ID. Expected Result is `<expectedValue>` (reviewer to validate/update) on every row whose outcome depends on the open decision, including `Validate` and read-back rows; setup, navigation and rows whose outcome the spec fixes keep literal results. Example: in a double-click case, the row for the first click keeps `Response status should be 201`, and the `Validate` row for how many bookings were stored gets `<expectedValue>`. The label comes from the scenario's Notes, so it is not a Labels contradiction in step 6 §4's check. A case not flagged in the Notes gets no extra label.

### 6.2 Automation readiness

Add `Gaps` and `Status` columns to the step 6 §5 progress table (not the CSV). They answer: what is missing from this plan that would stop someone automating the case? Judge only from the plan and the CSV; never read `tests/`, code or config to settle a gap.

They break step 6 §4's counts down per case. A Gaps cell lists every tag that applies, separated by `, `, each with a short reason in parentheses (inside a reason use `;` for commas and `\|` for pipes), or `NONE` alone. Example: `CONTRACT (error message undocumented), DATA (no price for Place A)`.

| Tag | When | Typical source |
| --- | --- | --- |
| `CONTRACT` | A checked value or call is undocumented: method, path, status code, response field, error shape | API case with no contract (step 6 §4 tally); `<expectedValue>`; `<method>`, `<apiPath>`; a response-side `<fieldName>`/`<value>` |
| `NAV` | A route, label or locator acted on is undocumented | "Navigation reference not found in loaded documents"; `<screenName>`, `<elementId>` |
| `DATA` | A business value the setup or assertion depends on is unstated (a place with no price when the total is asserted; a booking with no dates) | Precondition, Test Data, Expected Result; a request-side `<fieldName>`/`<value>` in Test Data, including an ID, price or limit |
| `THRESHOLD` | An asserted timeout, wait or bound is unknown | A `<value>` for a duration or bound |
| `VALIDATION` | The expected result rests on an unconfirmed rule or open question | Assumption risks, companion rules not `confirmed`, clarifications (including every `additional-test` case), open questions |
| `HOOK` | The plan does not say how to control timing or state (a race, the current time, a stalled process) or an external dependency (an email catcher, a forced failure). Fixed future dates need none | Concurrency, timing, date-boundary, integration and failure-path cases |

`DATA` is inputs, `CONTRACT` is what comes back; add `VALIDATION` too when only the spec owner can decide the value. Every placeholder marked (reviewer to validate/update) maps to a tag. Credential placeholders (section 0) are not gaps and take no tag.

**Status**, first match: `Blocked` if `CONTRACT`, `VALIDATION` or `THRESHOLD` remains; `Partial` if only `DATA`, `NAV` or `HOOK` remains; `Ready` if `NONE`.

**Roll-up** under the table: cases per Status (summing to the block count), cases per tag, the most common tag (all tied tags; "Nothing in the plan blocks automation." when every case is `NONE`), and the input source step 6 §5 records (spec file, architecture fallback or neither). Example:

```
Status: Blocked 3, Partial 2, Ready 5 (10 cases). Tags: CONTRACT 3, DATA 2, NAV 1.
Most common: CONTRACT. Inputs: spec file.
```

### 6.3 Validate before saving (adds to step 6 §4)

- Every row follows step 6 "Step Format", and flagged cases follow section 6.1.
- Gaps and Status follow section 6.2: every case has both, tags are from the six above, `NONE` is alone, every reviewer-marked placeholder in the case's rows maps to a tag, Status follows from the tags, and the roll-up counts add up.
- Report the flagged-case count and its clarifications in the completion summary.

## 7. Resume adds

Before step 5 or step 6 uses saved coverage rows:

- Run the level check (section 5): re-level any row that breaks section 2 except exempt rows. When step 5 has already written the plan (resuming into step 6), apply the re-level to the plan and the progress file as well, and say so in the report.
- Check the Test levels note and the coverage checklist exist; add them if missing.
- *(epic-level)* Check every flagged case has a clarification and every `additional-test` clarification lists a case, without adding a second row for a recorded decision.

## 8. Edit adds

### 8.1 Always

- **Load together.** *(epic-level)* When the edit target is an epic-level run's plan, progress file or CSV, edit step 1 also loads the other two by `{run_key}`. If one is missing, edit what exists and say which file was not updated. Knock-on updates below are part of the requested edit, not extra edits.
- Warn before adding a row at an excluded level. For a plan made before these rules, offer to re-level non-compliant rows and add the Test levels note, the coverage checklist and *(epic-level)* the readiness columns; apply only with the user's agreement.
- When the spec changes, re-check each affected case against section 4.4; unflag cases the spec now covers and record the answer in the clarification.
- *(epic-level)* When the edit touches the progress file or the plan behind it, list the affected case rows when restating the changes (edit step 1 and step 2 §1), then recompute their Gaps and Status and rewrite the roll-up.
- *(epic-level)* Steps written or changed follow step 6 "Step Format". When reformatting steps or filling placeholders, never change a case's meaning, order, Name, Priority, Folder, Labels or Test Level, or CSV `Status` (stays `Draft`). Change a Precondition only to drop setup a step now performs or to fill a placeholder under 8.2.
- *(epic-level)* **Drop or demote, in place.** Never re-run step 6, because it would overwrite fills and corrections. Drop: delete the case's CSV block and progress-table row, lower the coverage row's Test Count, remove the case from its clarifications' Scenarios (section 4.4). Demote: change Priority on the coverage row and on the first row of each affected CSV block. Surviving Scenario IDs stay as they are and a dropped ID is not reused. Recount blocks and rows in the progress file and plan.
- *(epic-level)* Unflagging (above) and a user-approved drop or demote are the only edits that change Labels, Priority or the case count.
- *(epic-level)* Keep the plan text, the progress table and roll-up, and the CSV identical.

### 8.2 Fills from code (epic-level; only when the user asks by name)

Three fills, named exactly **fill from code**, **fill with current behavior, expected results** and **fill with current behavior, steps or test data**. Each runs only when named, none implies another, and only the placeholders or tags the user names are filled. Reformatting steps to step 6 "Step Format" may be asked alone.

- **Reading.** Read code and config only; never run or modify anything. Find the code from the plan's references, spec and architecture docs and the repo layout, without assuming a language or file name; read only what the named placeholders need; ask when the location is unclear or ambiguous. Name an unbound control (no `for`/`id`, `aria-label` or nesting) by a normalized semantic name, such as `the check-in date field`, if it is unique on the screen; never use an id the code does not define. Otherwise leave the placeholder. Log it as "semantic name, not bound". Values that depend on environment (time zone, locale, runtime) stay placeholders, with the reason.
- **Fill from code.** Code-defined values only: route, method, field name, status code, error text, UI label or locator, including inside a Precondition. Never fill a placeholder behind a `VALIDATION` gap or an `additional-test` case; list the code value as a suggestion instead.
- **Fill with current behavior, expected results** and **fill with current behavior, steps or test data.** Only for placeholders behind a clarification or an `additional-test` case. A `VALIDATION` gap with no clarification (an unconfirmed companion rule or assumption) is not eligible for either fill; list the code value as a suggestion. Describe what the code does today in the plan's names and step 6 "Step Format"'s wording, including any display format the UI fixes; never call it correct or required. Clarifications, flags and `VALIDATION` stay, so the case stays `Blocked`; add "expected results (or steps) filled from current code behavior; spec owner to approve" to its first remaining Gaps reason. **Approval:** when the user reports the spec owner's decision, record the answer in the clarification, unflag the case (section 8.1), remove that Gaps note and `VALIDATION`, and recompute Status.
- **Differences.** If code and plan differ, even only in case or formatting of a label or message, do not fill around it: report, ask, and apply only what the user approves, logged as a user-approved correction. Earlier fills stay unless the user asks to remove them.
- **Gaps after a fill.** A tag clears when no placeholder that maps to it (section 6.2) remains in the case's rows.
- **Edit log.** Every value filled with its code location, every value left open with its reason, every step split (case, rows before and after), and for current-behavior fills, the clarification left open and a note that the values describe today's code, not an approved requirement.

## 9. Validate adds

Do not modify any output, and do not read code. Validate step 1: *(epic-level)* when given an epic-level plan, also load its progress file and CSV by `{run_key}`; a check whose file is missing is reported "not checkable: <file> missing", not passed. Run the level check (section 5); never FAIL or WARN on a checklist item that asks for an excluded level. Add these to the validation report:

| Check | Result if it fails |
| --- | --- |
| A `Unit` or `Component` row that is not an exempt row; a missing Test levels note | FAIL |
| A missing coverage checklist, or an item neither covered nor `N/A: <reason>` | FAIL |
| A duplicated or renumbered `CL-nn` ID; a clarification with Scenarios empty after step 4 | WARN |
| *(epic-level)* An `additional-test` label without a Notes flag, or the reverse; a flagged case with no clarification; an `additional-test` clarification listing no case; a flagged case with a literal expected result on a row that depends on the open decision (rows carrying the "filled from current code behavior" Gaps note are exempt) | WARN |
| *(epic-level)* A missing Gaps or Status column; a cell or roll-up that breaks section 6.3; a status the plan contradicts (for example `Ready` with a reviewer-marked placeholder left) | WARN |
| *(epic-level)* Step format: a row breaks a step 6 "Step Format" rule | WARN, in a **Step format** section listing failing case Names and step numbers |
