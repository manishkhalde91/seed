# ADR-09: Flyway with timestamped versions; migrate on startup locally, one-off run elsewhere

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** per-module migration folders; `V<yyyyMMddHHmm>__<module>_…`; Flyway on startup in `local`/`test`; profile `migrate` as a one-off run in shared environments; the app connects with DML-only credentials there.
- **Why:** easy local start; least privilege and no concurrent migrations in shared environments; no version collisions between branches.
- **Alternatives:** migrate on every instance start everywhere; Liquibase; sequential `V1__`.
- **Pros:** deterministic; fix-forward recovery.
- **Cons:** one extra deploy step; timestamps are less readable.
- **Scalability:** a carved-out module takes its folder with it.
