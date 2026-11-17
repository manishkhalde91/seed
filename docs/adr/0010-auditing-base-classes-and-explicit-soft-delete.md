# ADR-10: Auditing base classes and explicit soft delete

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** `AuditableEntity` / `SoftDeletableEntity`; `AuditorAware` from system scope → JWT subject → anonymous; UTC `Clock`; soft delete via explicit `markDeleted`, `@SQLRestriction` per entity, native restore.
- **Why:** consistent auditing; `@SoftDelete` cannot store `deleted_by`; the seed document forbids silent global filters.
- **Alternatives:** Envers; DB triggers; an embeddable audit block.
- **Pros:** automatic and testable.
- **Cons:** inheritance coupling; partial unique indexes; native restore SQL.
- **Scalability:** Envers or an audit trail can be added per module.
