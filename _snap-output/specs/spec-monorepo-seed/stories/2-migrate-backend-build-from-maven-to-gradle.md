---
title: 'Story 2 — Migrate the backend build from Maven to Gradle (Kotlin DSL)'
type: 'refactor'
created: '2026-11-13'
baseline_commit: '5afe1684147ecf8a9d4d35806bd750d56c15ab13'
status: 'done'
route: ''
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/stack.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/quality-and-delivery.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/stories-functional.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The backend builds with the Maven wrapper from story 1, but the spec (ADR-15, stack.md, quality-and-delivery.md §5) and every later story verify with `./gradlew clean build`.

**Approach:** Replace the Maven build with one Gradle project in Kotlin DSL: a committed, checksummed wrapper, a `gradle/libs.versions.toml` catalog holding every pinned version, Boot and Modulith BOMs via `enforcedPlatform()`, and Gradle equivalents of every Maven rule (Java 21, dependency convergence, Spotless). Same 31 tests, same results (SPEC CAP-2, ADR-15).

## Boundaries & Constraints

**Always:**
- Single project, `rootProject.name = "seed"`, group `com.company`, version `0.0.1-SNAPSHOT`; Boot Gradle plugin 4.1.2; BOMs via `enforcedPlatform()` (no `io.spring.dependency-management` plugin). Decided 2026-11-14 by the human: plain `platform()` makes `failOnVersionConflict()` fail on the Boot BOM's own resolution (e.g. jspecify 1.0.1 vs 1.0.0), and narrowing does not help.
- Every version lives in `libs.versions.toml`; the build scripts carry none. Exact pins only.
- Gradle 9.8.2 wrapper with `distributionSha256Sum`; wrapper jar matches Gradle's published wrapper checksum.
- Java toolchain 21; toolchain auto-download off in `backend/gradle.properties`.
- `failOnVersionConflict()` on every configuration; README *verify* item explains the `enforcedPlatform()` choice (stack.md risk 4).
- `test` runs every test class except `*IT` (not only `*Test`, so `SeedApplicationTests` keeps running); `integrationTest` runs `*IT` from the same source set and passes while none exist; `check` depends on both.
- Spotless: google-java-format, try 1.37.0 first and fall back to 1.36.1; `spotlessCheck` part of `check`.
- Same three test-only dependencies as `pom.xml` (jakarta.persistence-api, spring-data-commons, jackson-databind), test scope only, plus `junit-platform-launcher` as `testRuntimeOnly` (Gradle 9 no longer supplies it).
- README "First-time setup" says how Gradle finds JDK 21 (`JAVA_HOME` or an auto-detected install, listed by `./gradlew -q javaToolchains`).
- `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/` deleted in this change.

**Never:**
- No Java source or test changes; no JaCoCo, Sonar, OpenAPI, Dockerfile or CI (stories 3, 5).
- No global Gradle install; no JDK auto-download; no commits; leave `.claude`, `.cursor`, `.github/skills`, `_snap`, `docs/guidelines` alone.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Build | JDK 21, `./gradlew clean build` in `backend/` | passes; 31 tests (ModularityTest 3, ArchitectureTest 1, fixture test 26, SeedApplicationTests 1), 0 failures — the Maven baseline's real counts, corrected 2026-11-14 by the human (was 27) | — |
| No JDK 21 | no JDK 21 visible to Gradle (auto-download already off in the build) | fails with a toolchain message; nothing downloaded | toolchain error |
| Tampered download | distribution whose sha256 differs from `distributionSha256Sum` | wrapper refuses to install it | checksum-mismatch error |
| Version conflict | two versions of one module on a checked classpath | build fails naming the conflict | Gradle conflict error |
| Unformatted file | a Java file off google-java-format | `spotlessCheck` fails; `./gradlew spotlessApply` fixes it | — |
| No `*IT` yet | `integrationTest` with zero matching tests | task succeeds | `failOnNoDiscoveredTests = false` |
| Packaging | `./gradlew bootJar` | layered jar in `build/libs/` | — |
| Docs | README, PR template, `docs/` | no Maven command left (ADR-15 may name Maven as the replaced option); `docs/adr/` has ADR-15 | — |

</frozen-after-approval>

## Code Map

- `backend/pom.xml` -- what to port (versions, deps, Enforcer and Spotless rules), then delete with `mvnw`, `mvnw.cmd`, `.mvn/`.
- `backend/src/test/java/com/company/seed/SeedApplicationTests.java` -- ends in `Tests`: the `test` filter excludes `*IT` rather than including `*Test`.
- `README.md:10,18,31-32,104-109,135`, `.github/PULL_REQUEST_TEMPLATE.md:16` -- Maven commands to replace.
- `.gitignore:6-8`, `.gitattributes:6`, `.editorconfig:16-18` -- Maven patterns → `backend/.gradle/`, `backend/build/`, `backend/gradlew` LF, `[*.{kt,kts}]` indent 4.
- `docs/adr/README.md`, `docs/adr/0014-*.md` -- index and format for ADR-15 (`decisions.md:134-141`).
- Local only: Gradle 9.8.2 cached in `~/.gradle/wrapper/dists/`; JDK 21 at `/opt/homebrew/opt/openjdk@21` (PATH has 17).

## Things this relies on

- Gradle 9.8.2: dist sha256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`, wrapper jar sha256 `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98` -- services.gradle.org/versions/all
- Boot 4.1 supports Gradle 8.14+ and 9.x -- Spring Boot 4.1 system requirements
- Gradle plugins `org.springframework.boot` 4.1.2, `com.diffplug.spotless` 8.11.0 -- Gradle Plugin Portal
- `spring-boot-dependencies` 4.1.2 (manages `junit-platform-launcher`), `spring-modulith-bom` 2.1.2, `archunit-junit5` 1.4.2, google-java-format 1.37.0 and 1.36.1 -- Maven Central
- `failOnVersionConflict()`, `org.gradle.java.installations.auto-download` / `auto-detect`, `Test.failOnNoDiscoveredTests`, `javaToolchains` task -- Gradle 9 docs

## Tasks & Acceptance

**Execution:**
- [x] `backend/gradlew`, `gradlew.bat`, `gradle/wrapper/*` -- generate with Gradle 9.8.2 and the dist checksum; verify the jar checksum -- tamper-evident wrapper.
- [x] `backend/gradle/libs.versions.toml` -- versions, libraries, plugins from `pom.xml` -- one place for pins.
- [x] `backend/settings.gradle.kts`, `backend/gradle.properties` -- project name; auto-download off.
- [x] `backend/build.gradle.kts` -- plugins, toolchain 21, BOMs, dependencies, `failOnVersionConflict()`, `test`/`integrationTest` split, Spotless -- the build.
- [x] `backend/pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/` -- delete.
- [x] `README.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `.gitignore`, `.gitattributes`, `.editorconfig` -- Gradle wording and patterns; README verify items for the Gradle and gjf pins.
- [x] `docs/adr/0015-gradle-with-kotlin-dsl-for-the-backend-build.md`, `docs/adr/README.md` -- ADR-15 and index row.

**Acceptance Criteria:**
- Given the repository after this change, when a reviewer lists `backend/`, then no Maven file remains and `gradle-wrapper.properties` carries `distributionSha256Sum`.
- Given `./gradlew clean build` passed, when the test reports are counted, then the per-class counts equal the Maven run's.

## Implementation Notes

- Built 2026-11-13/14 by the implementation subagent; checked by the main agent against the diff.
- google-java-format 1.37.0 still throws under Spotless Gradle 8.11.0; 1.36.1 kept (allowed fallback, README verify item rewritten for Gradle).
- Test split selects class files (`**/*IT.class`, `**/*IT$*.class`), not `filter { }`: the ArchUnit engine's field tests cannot be matched by name and leaked into `integrationTest`. Probe `ZzProbeIT` confirmed the split, then removed.
- Maven baseline re-run on JDK 21 from a scratch copy of the baseline commit: 31 tests, same per-class counts as Gradle.
- `enforcedPlatform()` confirmed: with plain `platform()`, `compileJava` fails on jspecify, classmate and micrometer-observation conflicts. A temporary Guava probe proved unmanaged conflicts still fail.
- `-Porg.gradle.java.installations.auto-detect` is deprecated in Gradle 9.8; `-D` is the supported form.
- `bootJar` also leaves `seed-0.0.1-SNAPSHOT-plain.jar` (Boot plugin default); Homebrew's keg-only JDK 21 is not auto-detected, so `JAVA_HOME` must point at it on this machine.
- snap-guard `check --diff 5afe168…`: 0 findings.

## Review Triage Log

Pass 1 (2026-11-14). Layers: blind, edge-case, verification-gap, slop, security.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| 1 | edge | Spotless fails when the Gradle daemon runs on JDK 17 and JDK 21 is only the toolchain | high | Reproduced: `JAVA_HOME=17` + `installations.paths=<21>` → `:spotlessJava` FAILED. `gradle/gradle-daemon-jvm.properties` `toolchainVersion=21` → build green; with no 21 it fails "Cannot find a Java installation … languageVersion=21", nothing downloaded. | patch (G1) |
| 2 | edge | `gradlew` needs a launcher JVM; README implies auto-detection alone suffices | medium | Gradle 9 launcher requires Java 17+ on `JAVA_HOME`/PATH; README.md First-time setup doesn't say so. | patch (G1) |
| 3 | edge | Enforcer pinned the build JVM to 21; Gradle only pins the compile/test toolchain | high | Same root as #1: no daemon JVM criteria in the diff. | patch (G1) |
| 4 | blind | README JDK guidance omits Homebrew keg-only JDKs and the launcher-JVM requirement | medium | Homebrew `openjdk@21` not auto-detected (Implementation Notes); README lists only SDKMAN and standard folders. | patch (G1) |
| 5 | security | `enforcedPlatform()` silently overrides an explicitly declared version (e.g. a CVE bump) | medium | Reproduced: `implementation("org.jspecify:jspecify:1.0.0")` resolves to 1.0.1, no error. `resolutionStrategy.force(...)` overrides (build green); `strictly` constraint fails to resolve. | patch (G2) |
| 6 | blind | "as Maven's `dependencyManagement` did" is wrong for directly declared versions | medium | Same root as #5; README verify item and `build.gradle.kts` comment. | patch (G2) |
| 7 | verification-gap (other) | README §5 says the build "fails on any dependency version conflict" | medium | BOM-managed conflicts resolve to the BOM version; only unmanaged ones fail (#5). | patch (G2) |
| 8 | edge (claim) | Intent's "every Maven rule" not met for Java 21 and convergence wording | medium | Members of G1 and G2. | patch (G1, G2) |
| 9 | blind | `build` also writes `seed-0.0.1-SNAPSHOT-plain.jar` | low | Seen in `build/libs/`; a `*.jar` copy in story 5 matches both. Fix is one line. | patch |
| 10 | blind | `integrationTest` duplicates `test` setup | low | `useJUnitPlatform()` twice; future Testcontainers/JVM args on `tasks.test` would not reach `integrationTest`. | patch |
| 11 | slop | `gradle.properties:1` comment restates the property | low | Line 3 says the same; line 2 hint is the only addition. | patch |
| 12 | slop | `build.gradle.kts:56` comment restates `exclude(...)` | low | Direct deletion. | patch |
| 13 | slop | `build.gradle.kts:62` comment restates description/include/flag | low | Direct deletion. | patch |
| 14 | blind | Wrapper jar hash not recorded for a manual check | low | README verify item says "checked" but gives no hash; one-line fix. | patch |
| 15 | blind | "Every backend version is pinned in `libs.versions.toml`" overstated | low | Gradle version is in `gradle-wrapper.properties`, JDK in `.sdkmanrc`/toolchain. | patch |
| 16 | blind | No run command documented | low | Story 2 Scenario 5 "setup and run commands use `./gradlew`"; `./gradlew bootRun` verified to start (Tomcat up, `Started SeedApplication`). | patch |
| 17 | blind | `stack.md` risk 4 still says "narrow to runtime/test classpaths" | low | Planning doc now disagrees with the human-approved `enforcedPlatform()` decision; fix edits the spec set, not code. | defer |
| 18 | security | No `gradle/verification-metadata.xml` | medium | Pre-existing: the Maven build had no artifact checksum verification either. | defer |
| 19 | security | No automated wrapper-jar validation | medium | Jar matches published sha256 today; nothing checks future PRs. CI is story 3. | defer |
| 20 | verification-gap | No automated check that `ArchitectureTest`/`ModularityTest` keep running in `test` | medium | Gap filed pre-verified, disposition defer; needs CI (story 3). | defer |
| 21 | verification-gap | Version-conflict, no-JDK-21 and unformatted-file gates proven only by manual probes | medium | Gap filed pre-verified, disposition defer; no build-logic test harness, CI is story 3. | defer |
| 22 | security | `repositoriesMode` not locked in settings | low | Single project, one repository; fix adds new settings structure. Rejected: unlikely in everyday use and more than a direct correction. | reject |
| 23 | security | `.gitignore` lacks `local.properties`/`.kotlin`; credentials might go in tracked `gradle.properties` | low | No `.kotlin/` produced (`git status` clean after build); speculative future misuse. Rejected. | reject |
| 24 | security | Wrapper properties defaults | false | Reviewer reports them as fine (HTTPS, sha256, `validateDistributionUrl=true`). | reject |
| 25 | security | JDK 21 enforcement moved to toolchain — "no gap" | false | Not a defect claim; contradicted by #1 which is handled. | reject |
| 26 | blind / verification-gap (other) | ADR-15 names JaCoCo/Sonar and omits implementation decisions | false | `docs/adr/` files are verbatim copies of `decisions.md` (docs/adr/README.md); an ADR records the decision, not current state. | reject |
| 27 | blind | BOMs only reach `implementation`-based configurations | low | No `annotationProcessor`/`developmentOnly` dependency exists; a versionless one fails loudly at resolution. Rejected. | reject |
| 28 | blind | Build cache not enabled though ADR lists it as a pro | false | ADR lists a Gradle capability; nothing in the intent requires enabling it. | reject |
| 29 | blind | Spotless does not format `*.kts` | low | Needs a new pinned ktlint dependency — more than a direct correction; rejected. | reject |
| 30 | edge | `*ITCase`/`IT*` names run in `test` | false | Convention is `*IT` (quality-and-delivery.md §1); such tests still run, nothing is silently dropped. | reject |
| 31 | edge | Maven mirror/proxy settings no longer honoured | false | No `settings.xml`/`MVNW_REPOURL` in the repo; story 2 assumes Maven Central and Plugin Portal are reachable. | reject |

## Verification

**Commands:**
- `cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew clean build` -- expected: BUILD SUCCESSFUL, 31 tests, 0 failures.
- `JAVA_HOME=…openjdk@17 ./gradlew -Dorg.gradle.java.installations.auto-detect=false build` -- expected: toolchain failure, no download.
- `java -Djarmode=tools -jar build/libs/seed-0.0.1-SNAPSHOT.jar list-layers` -- expected: `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`.
- `git grep -n -E "mvnw|\bmvn\b|spotless:apply|maven-plugin" -- README.md .github/PULL_REQUEST_TEMPLATE.md docs` -- expected: no output.

## Tech Spec Critic Findings

- [low] check-5 | spec line 81 | In the intent's edge case "Keep 1.36.1 and the existing README *verify* item", "existing" can be read two ways: keep the item as it stands, which names `spotless-maven-plugin` 3.11.0 and `backend/pom.xml` (README.md:134-136), or keep a verify item rewritten for Gradle. The spec only says "README verify items for the Gradle and gjf pins", and under the first reading the `maven-plugin` grep on line 94 would fail.
- [low] check-5 | spec line 48 | In the "Version conflict" row, "two versions of one module on a checked classpath" can mean every resolvable configuration or only the runtime and test classpaths named in line 30's fallback. Under the second reading, a conflict on any other classpath lets the build pass, but intent Scenario 2 says such a conflict fails the build.
- [low] check-3 | spec line 43 | The README "First-time setup" guidance on how Gradle finds JDK 21 (line 34) is user-facing, but no I/O & Edge-Case Matrix row says what a new developer sees there. The "Docs" row covers only removing Maven commands and adding ADR-15.
- [low] check-3 | spec line 43 | The intent asks for `.gradle/` and `build/` in `.gitignore`. The matrix has no row for what the developer should see, which is that `git status` stays clean after `./gradlew clean build`. Line 61 records this only as a file edit.
