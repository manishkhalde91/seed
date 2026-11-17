# ADR-08: OIDC resource server with in-memory bearer tokens

- **Status:** Accepted
- **Date:** 2026-11-13
- **Source:** `_snap-output/specs/spec-monorepo-seed/decisions.md`

- **Proposal:** any OIDC provider; RS256 JWTs validated via JWKS; SPA login with code + PKCE; tokens in browser memory; roles `USER`/`ADMIN` from a configurable claim.
- **Why:** no password handling in the app; stateless API; the seed document asks to avoid `localStorage` for tokens.
- **Alternatives:** a backend-for-frontend with HttpOnly cookies (preferred by the seed document where feasible; needs CSRF protection and a session store); self-issued JWTs with local users.
- **Pros:** standard, stateless, provider-agnostic.
- **Cons:** tokens lost on reload need silent renew; XSS exposure lasts the life of a page (mitigated by CSP).
- **Scalability:** adding roles only changes claim mapping and `@PreAuthorize`.
