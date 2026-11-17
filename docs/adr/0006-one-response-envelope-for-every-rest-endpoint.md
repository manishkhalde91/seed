# ADR-06: One response envelope for every REST endpoint

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** `ApiResponse<T>` and `ApiErrorResponse` (api-standards.md §3) everywhere, including security errors.
- **Why:** one parsing path in the frontend; every response carries a correlation ID and timestamp.
- **Alternatives:** the seed document's variant (`traceId`, `items` inside `data`); RFC 9457 Problem Details (open question).
- **Pros:** consistent and self-describing.
- **Cons:** not an RFC standard; redundant with the HTTP status.
- **Scalability:** additive fields only; breaking changes via `/api/v2`.
