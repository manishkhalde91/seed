# ADR-03: One application, one image, no worker or broker

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** a single Spring Boot application; environment profiles; a one-off `migrate` profile; async work in-process.
- **Why:** the seed's scope is infrastructure only; a broker and a worker role would be unused complexity for most projects.
- **Alternatives:** api + worker roles with Kafka (the SCO approach); separate services.
- **Pros:** simple to run and understand.
- **Cons:** heavy background workloads compete with request threads.
- **Scalability:** a project that needs it adds a worker profile and a broker; module boundaries and events already prepare the seam.
