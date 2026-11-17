---
type: Guideline
title: "Context Lake Read Guideline"
description: "How snap-test-design reads the snapct context lake and builds the test plan for a spec-story run"
tags: [guideline, test-design, context-lake]
timestamp: "2026-10-13"
snap:
  consumers: [snap-test-design]
  loaded_by: _snap/custom/snap-test-design.toml
  applies_to: "spec-story runs (section 7, secrets, applies to every run)"
---

# Test Design: Context Lake Guideline

This guideline is loaded by `snap-test-design` through its team customization (`_snap/custom/snap-test-design.toml`).
It tells the skill how to read the snapct context lake and how to build the test plan for a spec-story run.

The project-specific facts are never written here. The skill reads them from the lake at run time.

## How to read this guideline

- Sections 1 to 6 apply **only to spec-story runs** (defined in section 1). In every other run (system-level, sprint epics, other input), ignore them and behave as the default skill.
- Section 7 (secrets) applies to **every run**, with no exception.

## 1. Applicability: spec-story run

A run is a spec-story run when the input is a spec folder `{project-root}/_snap-output/specs/<slug>/`, or any file in it (`stories-functional.md`, `stories.yaml`, `SPEC.md`).

- Accept exactly one spec per run. If the user names several, ask them to run once per spec.
- In step 1, a spec input counts as explicit Epic+Stories intent. Use epic-level mode even when PRD or architecture docs exist, and even when no sprint-status file exists.
- Set `epic_num` to `spec-` plus the spec folder name, normalized by step 1's slug rules (lowercase, hyphens, strip other characters, 64-character cap). So `run_key` is `epic-spec-<slug>` and never collides with a numbered sprint epic.
- If the user picks Create for a spec but names none, list the spec folders and ask which one. If exactly one exists, ask the user to confirm it.
- If the chosen folder has `SPEC.md` but no `stories-functional.md`, ask whether to use `stories.yaml` or to run the snap-spec story breakdown first.

## 2. Definitions

**Context lake.** The snapct context lake is `{project-root}/_snap-output/context/`. It is present when that folder holds `lake.yaml`.

- Paths in this guideline that start with `features/`, `prd/`, `api/`, `modules/`, `data-model/`, `architecture/`, `.cache/` or `blast-radius-map.json` are relative to the lake folder.
- `.cache/lake-index.yaml` is a machine index (FR -> feature file, feature slugs, endpoints, entities, BR IDs). It is git-ignored, so it may be absent on a fresh clone. Then resolve links from the feature files and `api/index.md`.
- `features/<slug>.md` is the hub for one feature.
- A rule ID cited but not defined in a feature file is shared and lives in `prd/product-rules.md`.

**SPEC vs lake.**

- `SPEC.md` describes **TARGET** behavior. Its capabilities, constraints and non-goals are the canonical contract. A verification companion states how risky capabilities must be verified.
- Context lake docs describe **CURRENT** behavior, as of the commit in their `source_commit` field.
- A SPEC companion that lives under the context lake is CURRENT context, not target contract. The SPEC body overrides it wherever they conflict.
- Each story in `stories-functional.md` maps one-to-one, by ID, to an entry in `stories.yaml`.

## 3. Context resolution

Do this in the workflow's create-mode step 2 (Load Context), before loading knowledge fragments.
On resume, if the checkpoint already has `## Context Manifest`, reuse it and do not rebuild it.

1. **Load the spec bundle:** `stories-functional.md`, `stories.yaml`, `SPEC.md`, any verification companion, and every path in the `SPEC.md` frontmatter `companions:` (resolved relative to `SPEC.md`). If any of these is missing, list it and ask the user before continuing. This step runs even when there is no context lake.
2. **Check for a lake.** If `{project-root}/_snap-output/context/lake.yaml` does not exist, record `no context lake` in the checkpoint and skip to step 6.
3. **Check freshness.** Compare the `source_commits` and `status` in `lake.yaml`, and the `source_commit` in `.cache/lake-index.yaml` when present, with the current git HEAD. Record any drift or a status other than `verified`. When either applies, treat every lake-derived CURRENT rule as an assumption risk.
4. **Find the feature file(s).** Stop at the first tier that yields a match:
   - a. `SPEC.md` companions that point into `features/` (authoritative).
   - b. `.cache/lake-index.yaml`, when present: look up each FR ID, feature slug, endpoint path, or entity name that appears in the stories or SPEC. An FR or slug hit gives the feature file directly. For an endpoint or entity hit, take the feature whose "Entry points and modules" section lists it.
   - c. Compare the story titles and the SPEC's problem statement with each feature file's title and "Purpose and scope". Ask the user to confirm tier-c matches before loading them.

   If nothing matches, say so and ask the user which feature applies. Do not guess.
5. **Follow the links.** For each matched feature, load the whole file once, then load:
   - Persona links -> `prd/personas/*.md`
   - Each ID on its `Rules:` lines that the file does not define -> its entry in `prd/product-rules.md`. If not there, search `features/*.md` for `- <ID> `. Record an ID that is defined nowhere, or only under a "Retired" heading, as a dangling ID and flag it as a gap. Never invent its meaning.
   - Its "States and transitions" section, if it has one
   - Each entry point -> its `api/endpoints/` doc (find it via `.cache/lake-index.yaml`, otherwise `api/index.md`). For an entry point the stories add or change that has no doc, record `no doc yet: new entry point`.
   - Each module -> `modules/index.md`, then `modules/<module>.md` (load only the parts about this feature's entry points, rules, and data)
   - Data the feature or its endpoint docs touch -> `data-model/entities/<entity>.md`
   - "Depends on" features that were not matched themselves -> only their purpose, functional requirements, business rules, validations, scenarios, and error handling
   - Its Q-n and D-n IDs -> matching entries in `prd/open-questions.md` and `prd/assumptions-log.md`
   - Product journeys in `prd/product-journeys/` that include this feature's journeys, personas, or entry points
   - `prd/entry-point-inventory.md` entries for the callers of this feature's entry points
   - Blast-radius dependents, read as described in section 4
   - Always, when present: `prd/integrations.md`, `prd/nfr.md`, `prd/constraints.md`, and every file in `architecture/`

   Do not load features, endpoints, or modules that no link reaches.
6. **Write the manifest.** Write `## Context Manifest` into the progress checkpoint. It contains:
   - a table: doc path | why loaded (which link from which file) | IDs taken from it
   - the freshness result
   - a CURRENT -> TARGET delta table listing every context item (FR, UJ, BR, VAL, SC, state or transition, entity field, endpoint contract) that a SPEC capability, constraint, or non-goal changes or removes, with the SPEC item that changes it

   Add every manifest path to `inputDocuments`. Use these docs as the PRD and architecture inputs and do not search for others. Show both tables to the user.

## 4. Reading blast-radius-map.json

- Module ownership is `module_boundaries.<module>.owns_files.glob`.
- The map is keyed by importer. A module's dependents are the modules whose `imports_from_modules[].module` equals it (`imported_by` names the importing module's files).
- Import edges miss runtime boundaries such as HTTP, queues, and events. So also count as dependents:
  - every caller of a changed entry point (from the endpoint docs and `prd/entry-point-inventory.md`)
  - every feature whose entry points live in a file the change touches
- If the map is absent, record that in the manifest and list regression scope as a risk.

## 5. What the plan must contain

### 5.1 Delta tests and regression tests

- For each CURRENT -> TARGET delta row, write a test that asserts the SPEC behavior, and a negative test that proves the old behavior is gone. Cite both the SPEC item and the context ID.
- When the old behavior never completes (for example, no response is sent), the negative test asserts a response within an explicit timeout.
- Context rules the SPEC does not touch become regression tests.

### 5.2 Unconfirmed rules

A rule is unconfirmed when its `confirmed:` field is anything other than `confirmed` and no inline `[CONFIRMED ...]` tag marks it, or when the two markers conflict.

- Give each unconfirmed untouched rule that the SPEC relies on, or that drives a P0 or P1 scenario, its own assumption risk.
- Group all other unconfirmed rules into one aggregated assumption risk.
- Rules in the delta table go in the delta only, not in the risk list.

### 5.3 System Integration Tests (SIT)

The plan includes a `## System Integration Tests` section right after the coverage plan. SIT rows in the coverage tables use Test Level `Integration` with a SIT tag.

SIT runs the real deployable services against real backing stores. Boundaries:

1. client -> service, using the auth mechanism and base-URL configuration the architecture docs describe
2. service -> data store: persistence, constraints, and atomicity under concurrent requests
3. service -> service
4. service -> each external integration that the matched features or the SPEC touch, using a local stand-in for that service rather than an in-process mock (integrations they do not touch are out of scope)

Derive cases from:

- (a) each feature journey and product journey, end to end
- (b) each error condition in the endpoint docs, and, for new entry points with no doc, the SPEC capabilities and story scenarios
- (c) each "Depends on" feature boundary
- (d) each blast-radius dependent, as regression

Also:

- When the deployment target cannot be reproduced locally (for example, serverless or a managed platform), state the closest runnable equivalent. List the behaviors it cannot prove as residual risks, each with an environment-specific check such as a staging smoke test.
- The section names its environment: service startup, data store, external-service stand-ins, seed data, and environment variables.
- Check which test frameworks, test environments, and CI the repository already has. List anything SIT needs that does not exist as a pre-implementation task.

### 5.4 Traceability

- Story scenarios are numbered `S<story>-AC<n>`, where n is the scenario's order within its story in `stories-functional.md`. `SC-n` stays reserved for context scenarios.
- Each test scenario cites, in a Trace column added to its coverage table:
  - its story ID, `S<story>-AC<n>`, and SPEC capability ID when it tests SPEC behavior, or `REGRESSION` when it tests an untouched context rule
  - every context ID it covers (FR, UJ, BR, VAL, SC)
  - the endpoint doc it exercises
- The coverage plan includes a matrix of manifest context IDs -> test scenario IDs.

## 6. Check before writing the output

Before step 5 writes the output, check that:

- every `S<story>-AC<n>`, every SPEC capability, and every FR, UJ, BR, VAL, and SC ID in the manifest maps to at least one test scenario, or is listed as out of scope with a reason (for example, a SPEC non-goal)
- the SIT section names its environment
- the SIT section has at least one case for each in-scope external boundary, each "Depends on" feature, each journey, and each blast-radius dependent

Close every gap before writing.

## 7. Secrets (applies to every run)

Never copy secrets, keys, credentials, or resource names found in code or context docs into any output of this workflow: the test plan, the progress checkpoint, the Context Manifest, the test-step CSV, or chat. Refer to them by their environment variable or config key name.
