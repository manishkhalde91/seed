# ADR-05: In-process async work with the Modulith event publication registry

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** `@ApplicationModuleListener` for work after commit; the JPA event publication registry keeps unfinished events and resubmits them on restart.
- **Why:** the triggering change and its event commit atomically, without a broker.
- **Alternatives:** `@Async` without a registry (events lost on crash); a PostgreSQL job table; Kafka.
- **Pros:** reliable enough for housekeeping and moderate background work; no new infrastructure.
- **Cons:** at-least-once delivery, so listeners must be idempotent; work runs on the instance that committed.
- **Scalability:** externalise events to a broker later (Modulith supports it) without changing publishers.
