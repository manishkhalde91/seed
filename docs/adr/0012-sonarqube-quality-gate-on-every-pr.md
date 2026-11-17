# ADR-12: SonarQube quality gate on every PR

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** Sonar analysis for Java and TypeScript with a new-code gate; skipped visibly when no server is configured.
- **Why:** one place for bugs, vulnerabilities, smells, duplication and coverage.
- **Alternatives:** CodeQL only; linters only.
- **Pros:** enforceable thresholds on new code.
- **Cons:** needs a server or cloud plan.
- **Scalability:** applies to new modules automatically.
