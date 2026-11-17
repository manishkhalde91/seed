---
type: Guideline
title: "Test Automation Guidelines"
description: "Standing rules for snap-test-automate when the input is manual test cases: read and check the file first (Part A), automate each test case as exactly one test (Part B), and choose hard or soft assertions (Part C)"
tags: [guideline, test-automate, test-cases]
timestamp: "2026-10-15"
snap:
  consumers: [snap-test-automate]
  loaded_by: _snap/custom/snap-test-automate.toml
  applies_to: "runs whose input is manual test cases (a file or pasted text) with ordered steps"
---

# Test Automation Guidelines

Standing rules for `snap-test-automate`, loaded through its team customization
(`_snap/custom/snap-test-automate.toml`). They apply when the user gives manual test cases (TCs) to
automate. If the input is not test cases (for example "expand coverage for module X"), neither part applies.

- **Part A** runs first, before the workflow's own steps: understand the file, show what was read, and
  check the cases have enough detail to execute.
- **Part B** governs how the cases are turned into tests: one TC, one `test()`.
- **Part C** governs assertion style: hard `expect()` when the flow depends on the check, `expect.soft()`
  when it does not.

---

# Part A: Read the Test Case File First

Its job is to understand the file's layout, extract each case, check there is enough to execute, and show
what was understood in the chat. It does not plan or generate anything.

## A.1 Detect the layout

Read the whole file (all rows, not a sample). Look at the header row and at how the first two cases are
laid out. Decide which layout it is:

| Layout | How to recognise it | How to parse |
|---|---|---|
| **A. One row per case** | Every row has a case name or ID, and the steps are inside one cell, separated by line breaks or numbering ("1. ... 2. ...") | One case per row; split the steps cell into steps. If a separate expected-results cell is also multi-line, pair step n with expected n by position; if the counts differ, say so |
| **B. First row carries the case, then one row per step** | A row has a case name/ID and its details; the rows below it have those cells blank and hold only a step (and its expected result, data) | A new case starts at each row with a non-blank name or ID; every following row with a blank name belongs to it |
| **C. Anything else** | Neither pattern fits, or the file mixes both | Do not guess. Say what you see and ask the user |

Do not assume any tool's template (Zephyr, TestRail, Xray or a custom one). Take the file as it is: read its
own header names and work out from the headers and the cell contents what each column holds (case name or ID,
precondition, objective, step action, step data, expected result, priority, labels, and so on). Report the
file's own header names in the layout verdict, next to what you understood each to be.

If a column's purpose is not clear, list it as "unclear" and ask the user. Do not drop it silently and do not
guess.

## A.2 Extract each case

For every case capture: ID (or the derived ID, see below), name, precondition, objective, folder, priority,
labels, and its ordered steps, each with action, data and expected result where present.

- If the file has no explicit TC ID, derive one and say how: use a scenario or requirement ID if the case
  names one (add a suffix when one ID has several cases, e.g. `SC-4-02`), otherwise number the cases
  `TC-01`, `TC-02` in file order. Never reuse one ID for two cases.
- Keep step wording exactly as written. Do not fix or reword anything at this stage.
- Do not infer missing values (endpoints, field names, test data). Note them as "missing" or "placeholder".
  Markers that mean "to be filled in or validated" (for example `<name>`, `<<key>>`, `TBD`, "to be
  confirmed") are reported as found, with a count per case.

## A.3 Show it in the chat

Print, in this order:

1. **Layout verdict:** the layout (A, B or C), the file name, the header row, and what each of the file's own
   columns was understood to hold (header -> meaning, plus any unclear columns).
2. **Summary table:** one row per case with columns `#`, `ID`, `Name`, `Priority`, `Steps`, `Placeholders`.
   Add the totals line: cases, steps, placeholders.
3. **First case in full:** its precondition, objective, and every step with action, data and expected, so
   the user can check the parse is right.
4. **Readiness check:** per case, whether it has enough detail to start executing (see A.4), and what
   is missing.
5. **Warnings:** anything that looks off: steps and expected counts differ, a case with no steps, duplicate
   names or IDs, rows that fit no case, derived IDs, unclear columns.

Keep it factual. Do not summarise or paraphrase the cases beyond the table.

## A.4 Check there is enough detail to execute

A case is ready to automate only if each step gives what an executor needs, without guessing:

- **Action:** what is done, specific enough to perform (for a UI step: the screen and element; for an API
  step: the method and URL, and the headers and request body it needs).
- **Test data:** every value the step uses (inputs, IDs, credentials by role, request fields) is stated for
  that step, in a data column, in the step text, or in the precondition. A step that needs a value and has
  none is missing data.
- **Expected result:** a checkable outcome (a status code, a visible text or element, a stored value), not a
  vague phrase.
- **Precondition:** the state and test data the case needs before step 1.

Do not fill gaps yourself and do not invent values. Placeholder markers (see A.2) count as missing until the
user supplies the value.

### Classify each missing value

Before listing a gap, classify the value that is missing:

| Class | Examples | What to do |
|---|---|---|
| **Generic** | person names, email addresses, phone numbers, street addresses, city, company name, free-text notes, dates with no rule attached | Generate it yourself (use the project's data factory or faker if one exists) and record it as generated |
| **Business-specific** | amounts, limits, thresholds or quantities that drive a rule; statuses, roles, categories or types the system defines; identifiers that must already exist or follow a system-defined format; any value a rule or the expected result depends on | Never invent it. It must come from the file or the user; list it as a gap |
| **Credentials / environment** | passwords, tokens, API keys, base URLs, user accounts per role | Never invent it. List it as a gap and ask |

Decide with these checks:

- Does the expected result, or a rule the case tests, depend on this exact value? Then it is business-specific.
- Must the value already exist in the system or satisfy a system rule (a known ID, an accepted format, a valid
  status)? Then it is business-specific.
- Is the value named in the spec, a business rule or the expected result? Then it is business-specific.
- Is it clearly free of any rule or dependency, so that any realistic value gives the same result? Then
  it is generic.

When in doubt, treat the value as business-specific and ask. A value that looks generic is business-specific
when something validates it, for example a phone number whose format or country is checked, or a name used in
a duplicate check.

In the readiness check, list generated values separately from gaps: case ID, step number, field, generated
value, and "generated (generic)". The user can override any of them. Do not carry generated values into
the plan as if the file had supplied them; mark them as generated wherever the data appears.

List every gap per case in the readiness check: case ID, step number, what is missing, and its class.

## A.5 Confirm before continuing

- If the layout is C, or any warning could change how cases are split, **stop and ask** the user to confirm
  or correct the reading. Wait for the answer.
- If the readiness check found any business-specific or credentials/environment gap, **stop and ask** the
  user for the missing details (or whether to proceed with stated assumptions for those steps). Ask once,
  grouped by case, and wait for the answer. Do not start the coverage plan while required details are
  missing unless the user says to proceed.
- Gaps that are only generic values do not stop the run. Generate them, show the list of generated values
  in the chat, and continue; the user can correct them at any time.
- Otherwise state "Parsed N cases from <file> as layout <A/B>; all cases ready; continuing" and carry on.

The parsed cases (with their IDs) are the input for the workflow's coverage-plan step and for Part B, which
needs a TC ID and ordered steps for each case.

---

# Part B: One Test Case = One test() Function

A test case with a sequence of steps is automated as one test, not split into several.

## WHEN PART B APPLIES

- **Create mode:** the input contains test cases (TCs), each with an ID (e.g. `TC-05`; IDs derived in
  Part A count) and an ordered list of steps.
- **Validate and Edit modes:** the spec was generated from such TCs (its test titles carry TC IDs).

If neither holds (e.g. "expand coverage for module X"), Part B does not apply. The examples are Playwright;
for other runners the same rule applies as one test function or flow per TC.

## THE RULE

1. **One TC = exactly one `test()`.** All of a TC's steps and expected results go into that one test, in the
   TC's order. Never split a TC (not per step, expected result, assertion or subject) and never merge two
   TCs. The test title contains the TC ID, and exactly one test title contains a given TC ID, so
   `--grep "TC-05"` selects it.
2. **Coverage plan (step 2).** List each TC as one entry with one test level, one priority and one target
   spec file. Do not turn a TC's expected results into separate scenarios or send parts of one TC to
   different workers.
3. **Steps.** Mark each step with `log.step('<n>. <step text>')`; do not wrap steps in `test.step`. The step
   text may be reworded to match the app (e.g. its real label) but must keep the TC step's intent: what is
   done and what is checked. Record differences between the TC and the app in code comments only; do not
   add `test.info().annotations.push(...)`.
4. **Hooks and fixtures** handle preconditions, seeded data and cleanup only. Every numbered TC step stays in
   the test body with its `log.step`, including steps that look like setup ("Navigate to /profile").

## PRECEDENCE

For TC input, this rule wins over anything in the skill that implies splitting a test, in Create, Validate
and Edit modes:

- the multi-`test()` templates in `steps-c/step-03a-subagent-api.md` and `step-03b-subagent-e2e.md`
- "Map acceptance criteria to test scenarios" in `steps-c/step-02-identify-targets.md`
- the "one assertion per test" / "tests are atomic" items in `checklist.md`
- "one concern per test" and "split monolithic tests" in `resources/knowledge/test-quality.md`

A validation or fix pass must not flag a TC test for being non-atomic, and must not split it.

## PASSING PART B TO SUBAGENTS

This guideline is loaded into the orchestrating agent only. When launching the generation workers in step 3,
include the full text of Part B in each worker's prompt, together with the test data Part A confirmed or
generated for each TC, and state that Part B overrides the worker's own step file and knowledge fragments on
how TCs are grouped into tests.

---

# Part C: Hard and Soft Assertions

Use `expect()` for a check the next steps depend on, and `expect.soft()` for a check they do not. A failed
soft check does not stop the test, but the test still fails at the end. Several soft checks can then
report their failures together in one run.

## WHEN PART C APPLIES

Whenever Playwright tests are generated, validated or edited for TC input (Part B), and for any other
generated Playwright test unless the user says otherwise. For other runners, use the runner's equivalent
(a non-fatal check that still fails the test), or `expect()` everywhere if there is none.

## THE RULE

1. **Hard `expect()`** when the flow depends on the check, because a failure would make the following steps
   meaningless or misleading. This includes:
   - an element that is about to be clicked or filled (a menu item, button, field);
   - arriving on the expected page or state (URL, heading, dialog open);
   - a success or confirmation message that the TC treats as proof the action worked;
   - the main outcome the TC exists to prove, and any precondition check later steps rely on;
   - every status-code or response check an API step needs before using its response in the next step.
2. **`expect.soft()`** when the flow can continue whatever the result, for example:
   - labels, headings, helper text and other text on a page the next step does not use;
   - the value shown in a field, a count, a badge or a column;
   - secondary expectations listed after the main outcome;
   - several independent checks on one screen.
3. **When unsure whether a check is load-bearing, use hard `expect()`.**
4. In the generated code, keep the `log.step` of the step (Part B rule 3) on the step; the choice of hard or
   soft is made per assertion inside the step, not per step.

## PRECEDENCE

For Playwright tests this rule wins over the "no soft assertion by default" items in
`resources/knowledge/evidence-integrity.md` (the table row on soft or optional modifiers, and the checklist
item "No soft assertion by default"). That fragment's concern, a check that can never go red, does not
arise here, because Playwright fails the test at the end when any `expect.soft()` fails; the rest of the
fragment still applies.

A validation or fix pass must not flag `expect.soft()` as a defect, and must not change a soft check to hard
or the reverse unless it breaks rules 1 to 3.

## PASSING PART C TO SUBAGENTS

Include the full text of Part C in each generation worker's prompt together with Part B, and state that it
overrides the worker's own step file and knowledge fragments on assertion style.
