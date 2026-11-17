# Security and RBAC

## 1. Authentication

**Decision:** OAuth 2.1 / OIDC from an external identity provider. The backend is only a **resource server**. The SPA uses authorization code + PKCE. The provider is configuration, not code.

| Option | Verdict |
|---|---|
| Any OIDC provider (Keycloak, Okta, Entra ID, Cognito, …), RS256 JWTs checked against JWKS | **Chosen.** No passwords in the app; MFA and lockout are the provider's; keys rotate without a deploy |
| Self-issued JWTs with a login endpoint and a local user table | Rejected: needs a password store, signing secret and refresh handling |
| Server sessions / backend-for-frontend with HttpOnly cookies | Not in the seed; adding it later needs CSRF protection and a session store (ADR-08) |

**Configuration** (all from environment variables):
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` = `${OIDC_ISSUER_URI}`.
- Audience validated by Spring's `JwtAudienceValidator` (`${OIDC_AUDIENCE}`).
- Signature, issuer, expiry and not-before are validated; clock skew 30 s.
- Access tokens are short-lived (set in the provider); refresh-token rotation is on.

**Local development** uses `mock-oauth2-server` with two documented demo users (`demo-user` → `USER`, `demo-admin` → `ADMIN`). Tests build tokens with `TestJwt`.

## 2. Authorization (RBAC)

- Roles come from a configurable claim (`${OIDC_ROLES_CLAIM}`, default `roles`), mapped by `JwtRoleConverter` to `ROLE_<NAME>`.
- Seed roles: `USER` and `ADMIN`; `ADMIN` implies `USER` through a `RoleHierarchy` bean. Real projects replace them with capability-based authorities.
- Role names live in one `Roles` constants class; controllers use `@PreAuthorize("hasRole(T(...Roles).ADMIN)")` or a meta-annotation, never scattered string literals.

**Deny by default**
- `authorizeHttpRequests`: on the management port, health (liveness, readiness and the root) and Prometheus are `permitAll` and every other exposed endpoint needs `ADMIN`; `/v3/api-docs` and Swagger UI are `permitAll` (readable without sign-in, because Swagger UI loads the document before Authorize) and are served only where docs are enabled (`local`, `dev`; elsewhere 404); everything else `authenticated()`. Every API call made from Swagger UI needs a token.
- `@EnableMethodSecurity`; every controller method carries `@PreAuthorize` (ArchUnit-enforced).
- A route-table test calls every endpoint with no token, with a role-less token and with a valid token.
- Ownership checks live in application services, using `CurrentActor`, and throw `ForbiddenOperationException`.

## 3. Endpoint access matrix (seed)

| Endpoint | ADMIN | USER | Anonymous |
|---|---|---|---|
| Health (liveness/readiness) | allowed | allowed | allowed |
| Sample read / create / update / export | allowed | allowed | denied |
| Sample delete / restore | allowed | denied | denied |
| Operation snapshot, artifact download URL | allowed | own only | denied |
| Prometheus (`/actuator/prometheus`) | allowed | allowed | allowed (management port only; the port is network-restricted, decided 2026-11-16) |
| Other Actuator endpoints (`loggers`, …) | management port only | denied | denied |
| `/ws` CONNECT | valid JWT; subscriptions checked per destination | | |

Each row has positive and negative tests.

## 4. CORS and CSRF

- **CORS:** allowed origins from `${CORS_ALLOWED_ORIGINS}` (`http://localhost:4200` locally). No wildcard with credentials, explicit methods and headers, `maxAge` 1 h. When frontend and API share an origin behind one proxy, CORS is off.
- **CSRF:** disabled, because the API is stateless and authenticates with bearer headers, not cookies. If cookies are ever introduced, CSRF protection with `CookieCsrfTokenRepository` is enabled first, and cookies are `Secure`, `HttpOnly`, `SameSite`.
- **WebSocket:** the token goes in the STOMP CONNECT header, never the URL; origins checked against the same list.

## 5. Secrets and configuration

- Nothing secret in git, images, `application*.yml`, `config.json` or committed env files. `application.yml` references environment variables only.
- `.env.example` documents every variable with placeholders; the real `.env` is git-ignored.
- Shared environments supply secrets from the platform's secret store; integration is a project concern.
- Gitleaks runs in pre-commit and CI.

Environment variables (placeholders):

```
SPRING_PROFILES_ACTIVE=local
DB_HOST=localhost
DB_PORT=5432
DB_NAME=seed_db
DB_USERNAME=seed_app
DB_PASSWORD=<local-secret>
OIDC_ISSUER_URI=http://localhost:8090/seed
OIDC_AUDIENCE=seed-api
OIDC_ROLES_CLAIM=roles
SWAGGER_UI_CLIENT_ID=seed-swagger
CORS_ALLOWED_ORIGINS=http://localhost:4200
STORAGE_ENDPOINT=http://localhost:9000
STORAGE_BUCKET=seed-artifacts-local
STORAGE_ACCESS_KEY=<local-only>
STORAGE_SECRET_KEY=<local-only>
```

## 6. Transport and headers

- HTTPS in every deployed environment; HSTS at the edge; `wss` for WebSocket.
- Security headers: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `frame-ancestors 'none'`.
- The static host sends a CSP: `default-src 'self'; script-src 'self'; connect-src 'self' <idp> <api> <ws>; object-src 'none'; base-uri 'self'; frame-ancestors 'none'`.
- Request limits (`common.limits`), all configurable:
  - **Body size:** every request body that is not `multipart/form-data` (other multipart types included) is at most `seed.limits.max-body-size` (`SEED_MAX_BODY_SIZE`, default `1MB` = 1,048,576 bytes; also the Tomcat form-post limit). A larger `Content-Length` → `413 PAYLOAD_TOO_LARGE` ("Request body exceeds 1MB") before authentication and before anything reads the body; a body without a length → 413 once it is read past the limit.
  - **Uploads:** `multipart/form-data` requests have the servlet limits instead, 10 MB per file and per request (`SEED_MAX_UPLOAD_SIZE`); larger → `413 PAYLOAD_TOO_LARGE` ("Upload exceeds 10MB"). The per-request limit counts the boundaries and part headers, so a single file must be a little under it. `SEED_MAX_UPLOAD_SIZE` also sets Tomcat's swallow size: a refused body up to that size is read to its end so the client gets the 413; a larger one may get a connection reset instead.
  - **Browser rules first:** CORS and the security headers are applied by a servlet filter ahead of the body limit, so every answer, the early 413 included, carries them.
  - **Write rate:** `POST`, `PUT`, `PATCH` and `DELETE` are limited to `seed.limits.writes-per-minute` (`SEED_WRITES_PER_MINUTE`, default 60) per caller (`CurrentActor`), as a token bucket that allows a burst of the full budget and refills continuously. Over budget → `429 RATE_LIMITED` with `Retry-After` (seconds until one token is available, rounded up). Reads are never limited. The check runs after authentication and before idempotency, so idempotent replays count. A write refused for its `Content-Length` costs no token; an oversized body without a length is found only while it is read, after the check, so it costs one.
  - **Per instance:** the write counters are kept in each instance's memory (behind the `RateLimiter` interface), so N instances allow a caller up to N × the limit. A shared store is a later, project-level change.

## 7. Database credentials

| Role | Used by |
|---|---|
| `seed_owner` (DDL) | `migrate` run; local/test startup |
| `seed_app` (DML only) | the running application in shared environments |

The database requires TLS in shared environments.

## 8. Logging

Never log credentials, tokens or authorization headers (errors-and-logging.md §4).

## 9. Frontend

- Tokens are held in memory (frontend-architecture.md §8); nothing auth-related in `localStorage`.
- Guards handle navigation only; the server decides.
- Angular sanitisation is never bypassed; `innerHTML` is not bound to user data.

## 10. Supply chain

- Backend versions pinned via BOMs and `gradle/libs.versions.toml`; the Gradle wrapper download is checked against `distributionSha256Sum` and validated in CI; `package-lock.json` committed; GitHub Actions pinned by commit SHA; base images pinned by digest.
- Dependabot or Renovate weekly; CycloneDX SBOM per build; Trivy blocks critical image findings; `npm audit` at high or above.
- No known critical or high issue is left unexplained at release.
