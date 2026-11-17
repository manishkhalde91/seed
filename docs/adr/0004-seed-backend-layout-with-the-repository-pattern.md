# ADR-04: Seed backend layout with the Repository Pattern

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:**
  - `config/`, `common/`, `security/`, `feature/<module>/{api,application,domain,infrastructure}`.
  - Repository interfaces in `domain/`; `Jpa<Aggregate>Repository` implementations in `infrastructure/persistence/`; domain always separate from JPA entities.
  - Non-database outbound interfaces in `application/port/` only when needed.
- **Why:** the seed document's structure; one consistent data-access shape; services testable without a database.
- **Alternatives:** services calling Spring Data directly (less code, couples services to JPA); repository interfaces in `application/port/` (open question); full hexagonal `ports.in/out` (more ceremony).
- **Pros:** clear boundaries, verified by ArchUnit and Modulith.
- **Cons:** an entity, a Spring Data interface and a mapper per aggregate.
- **Scalability:** a repository implementation can change technology without touching services; a module can be carved out with its package and schema.
