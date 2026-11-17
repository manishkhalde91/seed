# ADR-15: Gradle with Kotlin DSL for the backend build

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** `backend/` is one Gradle project with Kotlin DSL build scripts, a `gradle/libs.versions.toml` version catalog and a committed wrapper with `distributionSha256Sum`. Java toolchain 21; `test` and `integrationTest` tasks; Spotless, JaCoCo and Sonar Gradle plugins.
- **Why:** user decision (13 Nov 2026), superseding the Maven wrapper.
- **Alternatives:** Maven wrapper (the seed document and the original spec); Gradle with Groovy DSL.
- **Pros:** incremental tasks and build cache; type-checked build scripts with IDE completion; one catalog for every pinned version; the Boot plugin provides `bootJar` and `bootRun`.
- **Cons:** build logic is code and can grow into custom tasks; no Enforcer-style dependency-convergence rule out of the box; the Maven build from story 1 must be migrated.
- **Scalability:** a carved-out module becomes a subproject; shared build logic moves to convention plugins only then.
