# ADR-11: Angular Material (M3) themed from SCSS tokens

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** Material 21 used directly, themed via `mat.theme()` from tokens; wrappers only when they add behaviour.
- **Why:** accessible, maintained components; one source for brand values.
- **Alternatives:** custom kit on CDK; third-party kits.
- **Pros:** speed, accessibility, consistent theming.
- **Cons:** Material's look is customised through tokens only.
- **Scalability:** a custom design system later touches only tokens and `shared/ui`.
