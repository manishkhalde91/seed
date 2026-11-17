# ADR-01: Java 21, Spring Boot 4.1.x, Spring Modulith 2.1.x

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** pin Java 21 LTS, Boot 4.1.x and Modulith 2.1.x at their latest patch.
- **Why:** current support window (Boot 4.1 OSS to Jul 2027); Java 21 language features; Modulith 2.1 pairs with Boot 4.1. This supersedes the seed document's Java 17 / Boot 3.x baseline by explicit decision.
- **Alternatives:** Java 17 + Boot 3.5 (the seed document's default): older line, a costlier upgrade later. Boot 4.0: support ends around Dec 2026.
- **Pros:** Jackson 3, Jakarta EE 11, modular starters.
- **Cons:** springdoc 3.x trails Boot minors; Jackson 3 migration for any reused code.
- **Scalability:** upgrade minors about every 6 months with Dependabot.
