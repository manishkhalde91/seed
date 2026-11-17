# Snap Guardrails in CI (optional)

A CI check on merge requests is the one check nobody can skip. Whether a project has one is
the **team's choice**: Snap never edits a project's CI file. Copy a snippet below into your
own pipeline if you want it.

Start in **report-only** mode (the job shows findings but never fails the pipeline). Switch
to blocking once the team trusts the results.

## What the job needs

- Node 20 or later.
- Access to the `@snap/guardrails` package. It is in Index Nine's private npm registry:
  `https://gitlab.indexnine.com/api/v4/projects/idx9-internal%2Fsnap%2Fsnap-guardrails/packages/npm/`.
  Give the job a read token as a masked CI variable `SNAP_NPM_TOKEN`. If your CI cannot reach
  this registry (common on client infrastructure), do not add the job: the checks on each
  developer's machine still work.
- The full git history of the merge request (`fetch-depth: 0` / `GIT_DEPTH: 0`), so
  snap-guard can see what changed.
- The rule-pack version Snap shipped with is `guardrails-version` in `_snap/_config/manifest.yaml`; the snippets read it.

With only Node, most rules run. The security scan (opengrep), size checks (lizard, scc) and
the Python and Go dead-code checks need extra tools in the image; without them those checks
report "not run" and do not fail the job. `snap-guard doctor` lists what is missing.

## GitLab

```yaml
snap-guard:
  stage: test                      # use a stage your pipeline has
  image: node:20
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  variables:
    GIT_DEPTH: "0"
  allow_failure: true              # report only; set to false to block merges
  script:
    - REG="//gitlab.indexnine.com/api/v4/projects/idx9-internal%2Fsnap%2Fsnap-guardrails/packages/npm/"
    - npm config set "@snap:registry" "https:$REG"
    - npm config set "$REG:_authToken" "$SNAP_NPM_TOKEN"
    - npm install -g "@snap/guardrails@$(sed -n 's/^guardrails-version: *//p' _snap/_config/manifest.yaml)"
    - snap-guard check --diff "$CI_MERGE_REQUEST_DIFF_BASE_SHA"
```

## GitHub Actions

```yaml
name: snap-guard
on: pull_request
jobs:
  snap-guard:
    runs-on: ubuntu-latest
    continue-on-error: true        # report only; remove to block merges
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: |
          REG="//gitlab.indexnine.com/api/v4/projects/idx9-internal%2Fsnap%2Fsnap-guardrails/packages/npm/"
          npm config set "@snap:registry" "https:$REG"
          npm config set "$REG:_authToken" "$SNAP_NPM_TOKEN"
          npm install -g "@snap/guardrails@$(sed -n 's/^guardrails-version: *//p' _snap/_config/manifest.yaml)"
          snap-guard check --diff "${{ github.event.pull_request.base.sha }}"
        env:
          SNAP_NPM_TOKEN: ${{ secrets.SNAP_NPM_TOKEN }}
```

## Any other CI

Install the package as above, then run, from the repository root:

```sh
snap-guard check --diff <commit the merge request starts from>
```

Add `--sarif > snap-guard.sarif` for a SARIF report your CI can show.

## Exit codes

| Code | Meaning | Suggested handling |
|---|---|---|
| 0 | No blocking findings | Pass |
| 1 | Blocking findings | Fail, once the team turns blocking on |
| 2 | `_snap/_config/guardrails.yaml` has an error | Fail: fix the file |
| 3 | Some packages could not be checked (registry down) | Re-run; a person checks if it repeats |
| 4 | Only with `--ci`: a required tool is missing | Use `--ci` only in an image that has every tool |

## Strict mode

`snap-guard check --ci` fails when any external tool is missing, so every rule is sure to
run. Use it only in an image that has all the tools (opengrep, lizard, scc, ruff, vulture,
Go + deadcode, and the language builds).

## Protecting the guardrails settings

The coding agent cannot edit `_snap/_config/guardrails.yaml`, but a person can, in any merge request.
If your Git host allows it, make a senior engineer the code owner of that file and of the CI
file (GitLab and GitHub: a `CODEOWNERS` file), so changes to them need that person's review.
