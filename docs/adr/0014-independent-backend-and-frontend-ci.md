# ADR-14: Independent backend and frontend CI

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** one path-filtered `ci.yml` with backend and frontend jobs and an always-run `ci-gate` required check; separate Sonar projects; additive `/api/v1` changes.
- **Why:** the seed document requires independent build and test commands; a change on one side should not wait for the other.
- **Alternatives:** one pipeline for both; separate repositories.
- **Pros:** faster feedback; independent releases later.
- **Cons:** API compatibility discipline.
- **Scalability:** each new deployable adds its own filter and job.
