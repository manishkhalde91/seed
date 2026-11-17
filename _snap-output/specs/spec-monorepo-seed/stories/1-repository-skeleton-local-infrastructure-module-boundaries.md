---
title: 'Story 1 — Repository skeleton, local infrastructure and module boundaries'
type: 'feature'
created: '2026-11-13'
status: 'in-review'
baseline_commit: 'e7cb8f9d04ae5ad986110428325e2d939ba69a64'
route: ''
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/SPEC.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/backend-architecture.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/stack.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/security.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/decisions.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The monorepo holds only the spec; there is no code, no local infrastructure and nothing enforcing the module boundaries every later story relies on.

**Approach:** Create the seed tree: a Boot 4.1.2 backend under `com.company.seed` with `config`/`common`/`security` modules and a `feature/` root whose module detection is proven in this story; Modulith verification and ArchUnit rules in `./mvnw verify`; a minimal Angular 21 workspace on Node 24; `infrastructure/compose.yaml` with PostgreSQL and the mock OIDC server; object storage configurable through `STORAGE_*` variables only (no storage service installed; it arrives in a later story); root hygiene files, a README "First-time setup" section holding every setup step, and ADRs (SPEC CAP-1 infrastructure part, CAP-2).

## Boundaries & Constraints

**Always:**
- Layout per backend-architecture.md §1, §3, §4; package root `com.company.seed`; `@Modulithic(additionalPackages="com.company.seed.feature", sharedModules={"common","security"})`, `spring.modulith.detection-strategy=explicitly-annotated`, `common` is `OPEN`.
- `feature/` detection is settled in this story: a test proves a module under `…feature.<x>` is detected with this configuration. If Modulith 2.1.2 cannot detect it, record a README "verify" item and raise it at the done checkpoint.
- Repository interfaces belong in `feature/<m>/domain` (ArchUnit encodes it).
- Node 24 only: `.nvmrc` = 24, `package.json` `engines.node` = `>=24 <25` (the one deliberate range: any Node 24 release).
- Versions pinned exactly (no ranges, no `latest`); images pinned by tag + digest; ports bound to `127.0.0.1` and overridable from `.env`. A version or digest that cannot be confirmed at implementation uses the newest confirmed one and is listed as a README "verify" item.
- Object storage: `.env.example` holds `STORAGE_ENDPOINT`, `STORAGE_BUCKET` (`seed-artifacts-local`), `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE`; README "First-time setup" explains pointing them at any S3-compatible store and that the local storage service is added in a later story.
- README has one setup path ("First-time setup"); each infra command is written once as `docker compose …` with a single note that `podman compose` takes the same arguments.
- Backend and frontend build independently; no root build orchestrator. `.env.example` placeholders only; `.env` git-ignored.

**Never:**
- No object-storage service, bucket-init container or S3 client code; no feature module in `src/main`; no JPA/Flyway/security/web-endpoint code (stories 3–7); no CI workflow, Dockerfiles or Sonar (story 2); no Angular shell, auth or styling tokens (story 10).
- Boundary-violation fixtures never break `./mvnw verify`; they live under `src/test` and a dedicated test asserts each rule fails on them.
- Do not touch existing `.github/skills`, `.claude`, `.cursor`, `_snap`, `_snap-output`, `docs/guidelines`. No commits.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Infra up | `.env` from `.env.example`; `compose up -d` | `postgres` (`seed_db`, `seed_owner`, `seed_app`) and `oidc` on 8090 healthy | healthchecks |
| Reset | `compose down -v` then `up -d` | clean start, no manual steps | — |
| Port clash | 5432/8090 busy; `POSTGRES_PORT` / `OIDC_PORT` overridden in `.env` | services start healthy on the overridden ports | README documents it |
| Storage unset | no S3 store available | no storage service is started and no other service fails; `STORAGE_*` stay placeholders until the storage story | — |
| Demo sign-in | token requested from mock OIDC for `demo-user` / `demo-admin` | decoded token has `roles` = `["USER"]` / `["ADMIN"]` | — |
| Backend alone | JDK 21 only, in `backend/` | `./mvnw -B clean verify` passes; Enforcer rejects Java ≠ 21 and non-convergent deps | build fails with Enforcer message |
| Frontend alone | Node 24, in `frontend/` | `npm ci`, `lint`, `test:ci`, `build` pass | — |
| Feature detection | fixture app configured like production (`additionalPackages` = its own `…feature`, explicitly-annotated, `@ApplicationModule` `package-info` on `alpha`, `beta`) | detected as modules `alpha`, `beta` | README "verify" item if not |
| Boundary fixture | fixture classes violating each rule | each rule reports a violation; build stays green | — |
| Empty packages | rules whose subject packages have no classes yet | rules pass | `allowEmptyShould(true)` per rule |
| Hygiene | repo root | `.editorconfig`, `.gitattributes`, `.gitignore`, `.nvmrc`, `.sdkmanrc`, `.env.example`, README (one setup path), `.github/CODEOWNERS`, `.github/PULL_REQUEST_TEMPLATE.md`, `docs/adr/` ADR-01…14 all present; no secret committed; `.env` ignored | — |

</frozen-after-approval>

## Code Map

Greenfield. Paths relative to `/Users/viral.chonkar/projects/monorepo`:

- `backend/` -- generated via start.spring.io (Boot 4.1.2, Java 21, Maven, jar) for `mvnw`, `.mvn/wrapper`; then edited.
- `backend/src/main/java/com/company/seed/{SeedApplication.java, config/, common/, security/, feature/}` -- module roots with `package-info.java`; `feature` has none and stays empty.
- `backend/src/test/java/com/company/seed/architecture/` -- `ModularityTest`, `SeedArchitectureRules`, `ArchitectureTest`.
- `backend/src/test/java/com/company/fixtures/` -- violation fixtures + `ArchitectureRulesDetectViolationsTest`; `modulith/` fixture app with `@Modulithic(additionalPackages="com.company.fixtures.modulith.feature")`, explicitly-annotated detection, `feature/alpha` and `feature/beta` each with `@ApplicationModule` `package-info`, beta importing alpha's internal package.
- `frontend/` -- `ng new` (CLI 21.2.30) standalone, zoneless, SCSS, Vitest; angular-eslint; Prettier.
- `infrastructure/compose.yaml`, `infrastructure/postgres/init/01-roles.sh`, `infrastructure/oidc/config.json`.
- Root: `.editorconfig`, `.gitattributes`, `.gitignore`, `.nvmrc`, `.sdkmanrc`, `.env.example`, `README.md`, `.github/CODEOWNERS`, `.github/PULL_REQUEST_TEMPLATE.md`.
- `docs/adr/0001-…md` … `0014-…md` -- one per ADR in `_snap-output/specs/spec-monorepo-seed/decisions.md`.

## Things this relies on

- `spring-boot-starter-parent` 4.1.2 (manages `spring-boot-starter-test`, AssertJ 3.27.7, `maven-enforcer-plugin` 3.6.3, JUnit 6.0.3) -- Maven Central
- `spring-modulith-bom` 2.1.2 (built on Boot 4.1.2); property `spring.modulith.detection-strategy` -- Maven Central / Modulith docs
- `archunit-junit5` 1.4.2 -- Maven Central
- `spotless-maven-plugin` 3.11.0 with google-java-format 1.37.0 -- Maven Central
- Maven 3.9.16 via wrapper 3.3.4 -- start.spring.io (offers Boot 4.1.2)
- `@angular/cli` 21.2.30, `@angular/core` 21.2.26, `typescript` 5.9.3, `@angular-eslint/*` 21.4.0, `vitest` 4.1.12, `jsdom` 31.0.1, `prettier` 3.10.1 -- npm
- `postgres:16.16-alpine3.24` -- Docker Hub; digest pinned at implementation
- `ghcr.io/navikt/mock-oauth2-server:6.1.5` -- GHCR; digest pinned at implementation
- JDK 21 (`/opt/homebrew/opt/openjdk@21`), Node 24.21.0, Podman 6.1.3 + podman-compose -- local machine; Docker + Compose v2 and SDKMAN are supported alternatives documented in the README (not installed here)
- `security.md` §5 (variable list), `decisions.md` (ADR text) -- spec folder

## Tasks & Acceptance

**Execution:**
- [x] `backend/` -- generate from start.spring.io (web, validation, modulith); parent 4.1.2, Modulith BOM 2.1.2; add ArchUnit, Enforcer (Java 21, dependency convergence), Spotless -- runnable skeleton.
- [x] `backend/src/main/java/com/company/seed/**`, `application.yml` -- `SeedApplication`, `package-info.java` for `config`, `common` (OPEN), `security`; empty `feature` package; detection strategy -- module set from §3.
- [x] `backend/src/test/java/com/company/seed/architecture/*` -- `ModularityTest` (verify + module set exactly `config`,`common`,`security` plus any `feature.*`); `SeedArchitectureRules`: domain imports no `jakarta.persistence`/`org.springframework`/`..infrastructure..`/`..api..`; application imports no `..infrastructure..`/`..api..`/`jakarta.persistence`/`org.springframework.web..`; Spring Data interfaces only in `..infrastructure.persistence..`; `..api..` depends on no `*Repository`; repository interfaces only in `..domain..`; no field injection; no `com.fasterxml.jackson.databind` -- CAP-2.
- [x] `backend/src/test/java/com/company/fixtures/**` -- one fixture per rule; Modulith fixture app proving `feature.alpha`/`feature.beta` are detected and beta→alpha-internal is a violation -- proves rules bite and settles `feature/` detection.
- [x] `backend/src/test/java/com/company/seed/SeedApplicationTests.java` -- context loads.
- [x] `frontend/` -- scaffold with `jsdom` for Vitest; scripts `start`, `build`, `test`, `test:ci`, `lint`, `format:check`; `engines.node`; `package-lock.json`.
- [x] `infrastructure/**` -- compose: `postgres` and `oidc` with healthchecks, named volume, digest-pinned images; init creates roles from env; OIDC issuer `seed`, demo users `demo-user` (USER), `demo-admin` (ADMIN) via `roles` claim.
- [x] root files; `.env.example` (security.md §5 vars incl. `STORAGE_*`, plus infra ports and passwords); README "First-time setup" (prereqs: Docker or Podman, JDK 21, Node 24; `.env`; infra; storage configuration; backend; frontend; teardown; "verify" items); CODEOWNERS with placeholder team `@company/seed-maintainers` and a note to replace it; PR template.
- [x] `docs/adr/*` -- 14 ADR files, content from decisions.md, status Accepted.

**Acceptance Criteria:**
- Given only JDK 21, Node 24 and Docker or Podman, when README "First-time setup" is followed, then infra is healthy and the backend and frontend commands pass.
- Given the fixtures, when `ArchitectureRulesDetectViolationsTest` runs, then every rule and the Modulith internal-access check report a violation.

## Implementation Notes

- Verified 2026-11-13 by the orchestrator: `./mvnw -B clean verify` green (27 tests: ModularityTest 3, ArchitectureTest 7, fixture test 16, context test 1); frontend `lint`, `test:ci`, `build`, `format:check` green; infra healthy under Podman; issuer `http://localhost:8090/seed`; decoded tokens `demo-user` → `["USER"]`, `demo-admin` → `["ADMIN"]`; `down -v` + `up -d` healthy with no manual steps; `seed_owner` can DDL, `seed_app` cannot; `.env` only ignored.
- Deviation — npm pins held below spec by snap-guard SLOP-DEP-09 (packages < 7 days old): `@angular/cli`/`@angular/build` 21.2.29 (spec 21.2.30), `jsdom` 31.0.0 (31.0.1), `prettier` 3.9.10 (3.10.1); lockfile resolved `--before=2026-11-06`; `overrides.rollup` 4.66.0 (newer rollup pulls `@rollup/rollup-android-x64`, < 30 days). README "verify" item.
- Deviation — google-java-format 1.36.1 (spec 1.37.0): 1.37.0 throws on every file under spotless-maven-plugin 3.11.0. Spotless `removeUnusedImports` removed (it silently used unpinned gjf 2.0.0). README "verify" item + pom comment.
- Finding — Modulith 2.1.2 detects modules under `feature/` with the production configuration, but names them `feature.<x>` (e.g. `feature.sample`), not `<x>`. README documents it; later stories must use the full name.
- Test-only deps `jakarta.persistence-api`, `spring-data-commons`, Jackson 2 `jackson-databind` exist solely so violation fixtures compile; none on the main classpath.
- Rules exempt `..common.api..` from the "no api" checks (domain repositories may return `common.api.PageResult`, backend-architecture.md §5); the repository-location rule is scoped to `..feature..` because `common.idempotency` keeps its repository outside `domain`.
- `frontend/.npmrc`: `save-exact`, `engine-strict`, `ignore-scripts`; ESLint 9.39.5 per stack.md (angular-eslint had installed 10).
- README uses `--env-file .env` in every compose command (Docker Compose reads `.env` next to the compose file otherwise).
- Not exercised: `docker compose` path (no Docker here; README verify item); Enforcer rejecting a non-21 JDK (no other JDK installed).
- snap-guard: 0 blocking, 5 warnings (install-script packages esbuild, lmdb, msgpackr-extract, @parcel/watcher, fsevents; mitigated by `ignore-scripts=true`). OSV lookup, AI review, opengrep, lizard, scc did not run (TLS / tooling). `npm audit`: 25 moderate in dev tooling, none high.

## Spec Change Log

## Review Triage Log

Pass 1 (5 layers: blind, edge-case, verification-gap, slop, security). Generated files (`package-lock.json`, `mvnw`, `mvnw.cmd`, `favicon.ico`) were excluded from the review diff.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| 1 | verification-gap | Rules have no compliant fixtures; exemptions (`common.api`, Spring Data in persistence, repo outside feature, repo in domain) unguarded | medium | Pre-verified: deleting either exemption keeps all tests green | patch |
| 2 | verification-gap | `ArchitectureTest` lists rules separately from `SeedArchitectureRules.ALL` | medium | Pre-verified: dropping a field leaves every test green | patch |
| 3 | verification-gap | DML-only role privileges have no automated check | medium | Pre-verified; no DB test harness until the persistence story | defer |
| 4 | slop | `common.api` exemption untested | medium | Same defect as #1 | patch (with #1) |
| 5 | blind | Violation tests never assert a rule passes on valid code | medium | Same defect as #1; the "why" half is false: `rule.check` only throws on a violation and each fixture package isolates one breach | patch (with #1) |
| 6 | blind | `..api..` / `..infrastructure..` patterns also match third-party packages (e.g. `io.opentelemetry.api`) | low | `resideInAnyPackage("..api..")` matches any segment; an application class using such a library would fail the build | patch |
| 7 | blind | Module dependency directions (common→nothing, security→common) documented but unenforced | medium | No `allowedDependencies`; `verify()` only catches cycles and internal access, so `common`→`security` passes | patch |
| 8 | blind | Fixture does not prove unannotated packages are not modules | medium | alpha/beta both annotated, so the test passes under the default strategy too | patch |
| 9 | edge-case | Postgres healthcheck passes after a failed or skipped init | medium | `pg_isready -d postgres` ignores `seed_db`/roles | patch |
| 10 | edge-case | Init script failing partway leaves a data dir that never re-inits | low | Real; surfaced by #9's healthcheck plus README note | patch (with #9, #11) |
| 11 | edge-case + blind | Changing `DB_*` after first start has no effect; README silent | low | Init runs only on an empty volume | patch (README) |
| 12 | edge-case | `$` in `.env` passwords is interpolated by compose | low | Compose interpolates `--env-file` values | patch (README) |
| 13 | blind | README steps 5→6→7 `cd` into the wrong directory | low | `cd backend` then `cd frontend` fails | patch |
| 14 | blind | Root `.gitignore` `!.vscode/extensions.json` has no effect under `.vscode/` | low | git cannot re-include under an ignored directory | patch |
| 15 | security | `frontend/.gitignore` re-includes `.vscode/mcp.json` (may hold tokens) | low | CLI default; harmless today only because root ignores `.vscode/` | patch |
| 16 | security | Maven wrapper has no `distributionSha256Sum` | medium | `maven-wrapper.properties` downloads an unverified zip | patch |
| 17 | blind | No `format` script; PR template omits `format:check` | low | Asymmetric with `spotless:apply` | patch |
| 18 | blind | README omits that CI, Sonar and Dependabot come later | low | "Implemented so far" lists later stories but not CI | patch |
| 19 | slop | `app.ts` comment says "placeholder" | low | Doubt phrase in source comment | patch |
| 20 | slop | `styles.scss` CLI boilerplate comment | low | Scaffold text in an otherwise empty file | patch |
| 21 | blind | `mvnw`/`mvnw.cmd` missing | false | Present in the tree; excluded from the review diff as generated |  |
| 22 | blind | `package-lock.json` missing | false | Present; excluded from the review diff |  |
| 23 | blind | favicon / `public/` missing | false | `frontend/public/favicon.ico` exists |  |
| 24 | blind | Links into `_snap-output/` dead | false | `_snap-output/specs/spec-monorepo-seed/` is tracked in git |  |
| 25 | blind | `.sdkmanrc` id `21.0.13.0+9-tem` invalid | false | SDKMAN API lists `21.0.13.0+9-tem` for darwinarm64 |  |
| 26 | slop | CODEOWNERS placeholder team | false | Approved plan requires a placeholder team with a replace note (SPEC: project specifics are placeholders) |  |
| 27 | slop | `.env.example` "placeholders" wording | false | Required by the plan: placeholders only |  |
| 28 | slop | `STORAGE_*` unused | false | User directed storage to be configuration-only in this story |  |
| 29 | security | Mock OIDC issues ADMIN to anyone | false | Local mock by design, bound to 127.0.0.1; no non-local profile or consumer exists |  |
| 30 | security | `http://` issuer/storage defaults | false | Local-only values; nothing reads them yet; enforcement belongs to story 6 |  |
| 31 | security | Compose accepts placeholder passwords | low | Real but local-only on 127.0.0.1; a guard adds complexity | reject |
| 32 | security | DB passwords as container env vars | low | Local dev only; `*_FILE` secrets add complexity | reject |
| 33 | security | No Spring Security yet | false | Intent excludes security from this story (story 6) |  |
| 34 | security | `seed_app` gets TEMPORARY + DELETE | false | Matches the DML-only design in data-and-migrations.md §10 |  |
| 35 | blind + edge-case | `DB_PORT`/`OIDC_ISSUER_URI` must be kept in sync with ports by hand | low | Documented in README and `.env.example`; deriving needs extra plumbing | reject |
| 36 | edge-case | Domain rule flags `@NamedInterface` on a domain package | low | Possible but unlikely; fix adds an exclusion | reject |
| 37 | edge-case | `feature.a.b` nesting passes the module-set check | low | Unlikely layout; no named harm today | reject |
| 38 | edge-case | Repository-location rule only covers `..feature..` | low | Deliberate per Implementation Notes (`common.idempotency` per backend-architecture.md §4.3); widening needs a spec change | reject |
| 39 | edge-case | Domain/application may import `common.api` | false | Intended exemption (backend-architecture.md §5) |  |

## Design Notes

Fixtures sit outside `com.company.seed` so production rules (`ImportOption.DoNotIncludeTests`) never see them; the fixture test asserts `assertThatThrownBy(() -> RULE.check(fixtures)).isInstanceOf(AssertionError.class)`. Modulith fixture: `ApplicationModules.of(FixtureApp.class)` lists `alpha`, `beta`; `.verify()` throws `Violations`.

Mock OIDC demo users: `JSON_CONFIG` `tokenCallbacks` for issuer `seed` mapping `sub` → `roles` claim; interactive login on.

Verification shell env: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, `DOCKER_HOST` from `podman machine inspect`, `TESTCONTAINERS_RYUK_DISABLED=true`. Digests: `podman pull <image>` then `podman image inspect --format '{{.Digest}}'`.

## Verification

**Commands:**
- `podman compose -f infrastructure/compose.yaml up -d && podman compose -f infrastructure/compose.yaml ps` -- expected: `postgres`, `oidc` healthy
- `curl -s localhost:8090/seed/.well-known/openid-configuration` -- expected: issuer `http://localhost:8090/seed`
- `curl -s -d grant_type=client_credentials -d client_id=demo-admin -d client_secret=x localhost:8090/seed/token` then decode the JWT payload -- expected: `roles` = `["ADMIN"]` (and `["USER"]` for `demo-user`)
- `cd backend && ./mvnw -B clean verify` -- expected: BUILD SUCCESS incl. ModularityTest, ArchitectureTest, fixture test
- `cd frontend && npm ci && npm run lint && npm run test:ci && npm run build` -- expected: all pass
- `podman compose -f infrastructure/compose.yaml down -v && podman compose -f infrastructure/compose.yaml up -d` -- expected: healthy again
- `git status --porcelain --ignored | grep -E '\.env$'` -- expected: `.env` listed only as ignored (`!!`)

**Manual checks:**
- `docker compose` path not run (no Docker on this machine); compose file uses only features both support.

## Tech Spec Critic Findings

- [SOLVED] [high] check-6 | spec line 77 | Angular 21's Vitest unit-test setup requires a DOM environment package (jsdom or happy-dom) for `test:ci` to pass, but none is listed/pinned.
- [SOLVED] [medium] check-1 | spec line 27 | Intent requires that any unconfirmable pinned version use the newest confirmed patch and be marked "verify" in the README; the spec only applies the "verify" rule to `feature/` detection, leaving no instruction for unconfirmable versions (e.g. image digests deferred to implementation).
- [SOLVED] [medium] check-5 | spec line 64 | "same `@Modulithic` shape" leaves open whether the fixture's `feature/alpha`/`feature/beta` carry `@ApplicationModule` `package-info` under `explicitly-annotated` detection and whether `additionalPackages` points at the fixture's `feature` package — one reading proves the production config, the other proves nothing.
- [SOLVED] [medium] check-3 | spec line 91 | OIDC demo users `demo-user` (USER) / `demo-admin` (ADMIN) with a `roles` claim are developer-visible (they log in with them) but have no I/O matrix row and no Verification command proving a token carries the role.
- [SOLVED] [medium] check-5 | spec line 53 | "hygiene files present" does not enumerate the intent's required set (`.editorconfig`, `.gitattributes`, `.gitignore`, `.nvmrc`, `.sdkmanrc`, `.env.example`, README one setup path, CODEOWNERS, PR template, `docs/adr/` ADR-01…14), so the row can pass with a partial set.
- [SOLVED] [medium] check-6 | spec line 75 | `spotless-maven-plugin` is pinned but the google-java-format version it runs is not, contrary to the exact-pinning rule at line 30.
- [SOLVED] [low] check-6 | spec line 80 | Docker / Docker Compose (a supported prerequisite per intent and README) and SDKMAN (for `.sdkmanrc`) are not listed among local-machine dependencies.
- [SOLVED] [low] check-6 | spec line 9 | `security.md` (§5 variable list) and `decisions.md` (ADR content) are required inputs for Tasks lines 92–93 but appear in neither `context:` nor "Things this relies on".
- [SOLVED] [low] check-3 | spec line 46 | Port clash row's Expected Output is a user action ("user sets `POSTGRES_PORT` / `OIDC_PORT`"), not the observable result (services start healthy on the overridden ports).
- [SOLVED] [low] check-5 | spec line 47 | "nothing starts or fails" is ambiguous: "no storage service starts and nothing fails" vs "nothing starts, or startup fails".
- [SOLVED] [low] check-5 | spec line 30 | "Versions pinned exactly (no ranges)" conflicts with the `engines.node` range `>=24 <25` at line 29; unclear whether Node is an intended exempt range or should pin 24.21.0.
