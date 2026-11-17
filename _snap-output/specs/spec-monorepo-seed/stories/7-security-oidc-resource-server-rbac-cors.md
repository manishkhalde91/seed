---
title: 'Story 7 (7a) — Security: OIDC resource server, RBAC, CORS and token-dependent items'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_commit: 'b1ef4621c37745064b9026a5d2b3e5e8e74c4651'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/security.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Every endpoint is open. There are no tokens, roles or CORS rules, and the work deferred until "a token exists" is still waiting (CAP-8):
- `user:<sub>` auditing and `user.id` logging;
- caller-scoped idempotency;
- locking down the management port and the API docs.

**Approach:** Packet 7a (decided 2026-11-16, answer "S"; 7b request limits is deferred to its own pass).

- **Security module:** `com.company.seed.security` becomes a JWT resource server: issuer, audience and expiry validation, configurable role claim, `ADMIN` ⇒ `USER`, deny by default, `@PreAuthorize` enforced by ArchUnit, CORS, envelope 401/403.
- **Token-dependent items from deferred-work.md:** `CurrentActor` gains `user:<sub>`, log lines gain `user.id`, `IdempotencyFilter` moves after the security chain and scopes keys to the caller, `/actuator/loggers` and health details are restricted to ADMIN, and the docs are secured.
- **Decisions (2026-11-16):**
  - Prometheus stays open on the network-restricted port 8081 (option a).
  - `dev` docs (renegotiated at checkpoint 1, option a): `/v3/api-docs` and Swagger UI stay readable without sign-in, because Swagger UI must load the spec before Authorize. Every API call made from the UI needs a token from Authorize with OIDC + PKCE.
  - Fail-fast startup on a missing mapped table is kept, and CAP-7's wording is updated (option a).
  - Replays (checkpoint 1, option a): 401, 403, 408 and 429 are never stored. A replay passes authentication but not `@PreAuthorize`, so a caller who lost a role within the retention window can still replay their own earlier successful response (nothing new is written). This is accepted and documented.

## Boundaries & Constraints

**Always:**
- **Token validation:**
  - signature through the issuer's JWKS (`OIDC_ISSUER_URI`, resolved lazily so startup never needs the issuer reachable);
  - issuer, `exp`/`nbf` with 30 s clock skew;
  - audience must contain `OIDC_AUDIENCE`.
  - A token failing any check → 401 `UNAUTHENTICATED`.
- **Roles:** `JwtRoleConverter` reads the claim at `OIDC_ROLES_CLAIM` (default `roles`; a dotted path such as `realm_access.roles` walks nested objects; a list of strings) and maps each entry, upper-cased, to `ROLE_<NAME>`. A `RoleHierarchy` makes `ADMIN` imply `USER`. Role names live only in `security.Roles` (`USER`, `ADMIN`).
- **Deny by default** (`authorizeHttpRequests`):
  - application port: `/v3/api-docs/**` and `/swagger-ui/**` per the docs rule below; everything else `authenticated()`;
  - management port (`EndpointRequest`): health is `permitAll` (liveness, readiness and the root; the root because load balancers often probe it, with status only unless ADMIN); `prometheus` is `permitAll`; `loggers` and any other exposed endpoint need `ADMIN`. Endpoints that are not exposed answer 404 to a signed-in caller and 401 to an anonymous one (renegotiated by the human on 2026-11-16, option a: stricter, so outsiders cannot list endpoints).
  - `@EnableMethodSecurity`.
  - A new ArchUnit rule: every public handler method of a `@RestController`/`@Controller` under `com.company.seed` carries `@PreAuthorize`, directly or through a meta-annotation. It gets a violating fixture and a compliant one.
- **Error envelopes:**
  - 401 and 403 from the filter chain go through an `AuthenticationEntryPoint` and an `AccessDeniedHandler` that use `ApiErrorResponseWriter`. A 401 carries `WWW-Authenticate: Bearer`.
  - `GlobalExceptionHandler` maps `AccessDeniedException`/`AuthorizationDeniedException` → 403 `FORBIDDEN` (WARN) and `AuthenticationException` → 401 `UNAUTHENTICATED` (INFO).
  - Bodies never echo the token or the validation reason, and every 401 and 403 envelope carries the correlation ID.
- **CORS, CSRF and headers:**
  - CORS origins from `CORS_ALLOWED_ORIGINS` (comma-separated, no wildcard); methods `GET, POST, PUT, PATCH, DELETE, OPTIONS`; headers `Authorization, Content-Type, X-Correlation-Id, Idempotency-Key`; exposed headers `X-Correlation-Id, Location, Idempotent-Replayed, Retry-After`; `maxAge` 1 h.
  - CSRF disabled; sessions `STATELESS`.
  - Headers: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: frame-ancestors 'none'`.
- **Actor and logging:**
  - `CurrentActor` resolves the open `SystemActor` scope, then the authenticated JWT as `user:<sub>`, then `anonymous`.
  - After authentication, MDC `user.id` = `sub`, so every later log line of the request carries it, the request summary line included. It is cleared with the request's other MDC keys.
  - Unauthenticated or rejected requests carry no `user.id`.
  - Inside a `SystemActor` scope, `user.id` is the scope name (`system:*` or `user:*`), restored on exit.
- **Idempotency:**
  - `IdempotencyFilter` runs after the Spring Security filter chain: order `-90`, after the chain's default `-100`.
  - Its records are scoped to `CurrentActor.id()`: a new migration first deletes existing rows (they are temporary and have no caller), then adds `caller text NOT NULL`; uniqueness becomes `(caller, idempotency_key)`.
  - 401, 403, 408 and 429 responses are never stored; the key is released.
  - The same key from two callers is two independent records.
- **Docs and health:**
  - Docs, where enabled (`local` and `dev`): `/v3/api-docs/**` and Swagger UI are `permitAll`. In the default profile they are disabled (404). API calls from Swagger UI carry the bearer token.
  - Swagger UI is configured for OIDC authorization code + PKCE against the issuer (`springdoc.swagger-ui.oauth.*`, client id `${SWAGGER_UI_CLIENT_ID:seed-swagger}`).
  - The exported `api.yaml` gains only an `http` bearer-JWT security scheme as the global requirement; it stays deterministic.
  - Health `show-details: when-authorized` with role `ADMIN` now takes effect.
- **Configuration:** a validated `@ConfigurationProperties` record for audience, roles claim, CORS origins and the Swagger client. A missing audience or issuer fails startup.
- **Tests:**
  - `TestJwt` mints RS256 tokens with a test key pair.
  - The test `JwtDecoder` uses the production validator bean, so the validation rules are tested.
  - All existing integration tests send a `USER` token by default.

**Never:**
- Request size limits, multipart limits, rate limiting (7b).
- Login endpoints, sessions or cookies.
- Local user or role tables.
- Ownership checks (story 8, with the first owned resource).
- Tokens or `Authorization` headers in logs.
- Roles written as string literals outside `Roles`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No token | any application route | 401 `UNAUTHENTICATED` envelope with `WWW-Authenticate: Bearer` and the correlation ID | INFO |
| Bad token | expired beyond 30 s; wrong issuer; wrong audience; bad signature; `alg: none` | 401 each; body never contains the token or the reason | INFO |
| Skew | token expired 20 s ago | accepted | N/A |
| Roles | token with `roles: [USER]` / `[ADMIN]` / none, calling a `USER`-only and an `ADMIN`-only probe method | USER → 200/403; ADMIN → 200/200 (hierarchy); none → 403/403 | WARN on 403 |
| Custom claim | `OIDC_ROLES_CLAIM=groups`, token with `groups: [ADMIN]` | ADMIN access | N/A |
| Management port | anonymous `/actuator/health/liveness`, `/readiness`, `/prometheus`; `/actuator/loggers` anonymous, as USER, as ADMIN; `/actuator/health` as ADMIN | 200, 200, 200; 401, 403, 200; health shows `components` only for ADMIN | N/A |
| Docs | `local` and `dev`: anonymous `/v3/api-docs` and Swagger UI; default profile | 200, 200; 404 (disabled) | N/A |
| CORS | preflight from an allowed origin; from another origin | 200 with the allow headers; 403, no `Access-Control-Allow-Origin` | N/A |
| Headers | any response | `nosniff`, `no-referrer`, `frame-ancestors 'none'` present | N/A |
| Auditing | a `user:` write through a probe persisting `AuditedThing` with token `sub=u-1` | `created_by` = `user:u-1`; inside `SystemActor.run("system:x", …)` → `system:x` | N/A |
| Logging | authenticated request with `sub=u-1`; anonymous request; a log line inside `SystemActor.run("system:x", …)` | the handler's line and the summary line carry `"user.id":"u-1"`; no `user.id` member; `"user.id":"system:x"`; the token never appears in output | N/A |
| Idempotency | same key and body from `sub=a` then `sub=b`; replay by `sub=a` with no token; replay by `sub=a` with a token; a keyed request rejected by `@PreAuthorize` (403), then retried | two independent 201s; 401 (never a stored response); the stored 201 with `Idempotent-Replayed: true` and the security headers; both attempts are 403 from the handler and no row is kept | N/A |
| Method security | `@PreAuthorize` denial inside a controller | 403 `FORBIDDEN` envelope (not 500) with the correlation ID | WARN |
| Roles claim shape | `OIDC_ROLES_CLAIM=realm_access.roles`, token `realm_access: {roles: [admin]}` | ADMIN access (nested path, upper-cased) | N/A |
| Missing config | `OIDC_AUDIENCE` unset; `OIDC_ISSUER_URI` unset | startup fails; the message names the missing property | N/A |
| ArchUnit | a fixture `@RestController` method without `@PreAuthorize` | the new rule fails on it and passes on the compliant fixture | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/security/package-info.java` -- closed shared module, `allowedDependencies = "common"`. Security code lives here: config, converter, handlers, `Roles`, the actor.
- `backend/src/main/java/com/company/seed/common/actor/{CurrentActor,DefaultCurrentActor,SystemActor}.java` -- `SystemActor.current()` is package-private (line ~54); expose read access for the security actor. Resolve the two `CurrentActor` beans (`@Primary` in security, or by replacing the default).
- `backend/src/main/java/com/company/seed/common/idempotency/IdempotencyFilter.java` (`ORDER` line ~87) and `infrastructure/persistence/JpaIdempotencyRecordRepository.java` -- re-order; add the caller to claim, find, take-over, complete and release.
- `backend/src/main/resources/db/migration/common/V202611161100__common_create_idempotency_record.sql` -- shipped; add a NEW migration for `caller` and the new unique constraint.
- `backend/src/main/java/com/company/seed/common/logging/{CorrelationIdFilter,RequestLoggingFilter}.java` -- `CorrelationIdFilter` clears MDC at the end of the request (keep `user.id` until then).
- `backend/src/main/java/com/company/seed/common/error/web/{GlobalExceptionHandler,ApiErrorResponseWriter,StatusMapping}.java` -- the new security mappings; `ApiErrorResponseWriter.write(...)` for the entry point and handler.
- `backend/src/main/java/com/company/seed/config/OpenApiConfig.java` -- add the bearer security scheme; `api.yaml` must be regenerated with `updateOpenApi`.
- `backend/src/main/resources/application{,-local,-dev}.yml`, `.env.example` (has `OIDC_*`, `CORS_ALLOWED_ORIGINS`), `infrastructure/oidc/config.json` (demo users `demo-user`/`demo-admin`, issuer id `seed`) -- configuration.
- `backend/src/test/java/com/company/fixtures/web/LenientRestClient.java` and the 11 test classes using `RestClient`/`MockMvc` -- give them a default `USER` token. `GlobalExceptionHandlerTest` (`@WebMvcTest`) needs security handled.
- `backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java` -- add the `@PreAuthorize` rule to `ALL`; fixtures under `fixtures/archunit`.
- `_snap-output/specs/spec-monorepo-seed/SPEC.md` (CAP-7 success line) -- amend per decision (a).

## Things this relies on

- `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` (Spring Security 7.1.2), `spring-boot-starter-security-test` -- Boot 4.1.2 BOM
- `org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest` -- spring-boot-security 4.1.2
- `NimbusJwtDecoder.withIssuerLocation`/`withPublicKey`, `SupplierJwtDecoder`, `JwtValidators.createDefaultWithIssuer`, `JwtTimestampValidator(Duration)`, `OAuth2TokenValidator`, `JwtAuthenticationConverter` -- Spring Security 7 OAuth2 resource server
- `RoleHierarchyImpl.withDefaultRolePrefix()`, `@EnableMethodSecurity`, `@PreAuthorize`, `AuthorizationDeniedException`, `AuthenticationEntryPoint`, `AccessDeniedHandler`, `CorsConfigurationSource` -- Spring Security 7
- Nimbus JOSE + JWT (signing test tokens) -- comes in with the resource-server starter
- `springdoc.swagger-ui.oauth.{client-id,use-pkce-with-authorization-code-grant}`, `io.swagger.v3.oas.models.security.SecurityScheme` -- springdoc 3.2.0 / swagger-core 2.2.55
- `OIDC_ISSUER_URI`, `OIDC_AUDIENCE`, `OIDC_ROLES_CLAIM`, `CORS_ALLOWED_ORIGINS` -- `.env.example`; new `SWAGGER_UI_CLIENT_ID`
- `mock-oauth2-server` issuer `seed` with `demo-user`/`demo-admin` -- `infrastructure/oidc/config.json`
- ArchUnit 1.4.2 (new rule), Spring Modulith 2.1.2 (closed `security` module, `allowedDependencies = "common"`), Flyway 12 (new migration), Testcontainers 2 + Docker (ITs), SLF4J MDC with Boot's ECS structured logging (`user.id`) -- already in the build
- `management.server.port` (8081), `management.endpoint.health.{show-details,roles}`, `management.endpoints.web.exposure.include` (add `loggers`), the per-profile `springdoc.{api-docs,swagger-ui}.enabled` flags, and the Gradle `updateOpenApi`/`checkOpenApi` tasks -- stories 5 and 6

## Tasks & Acceptance

**Execution:**
- [x] `libs.versions.toml`, `build.gradle.kts` -- the security, resource-server and security-test starters.
- [x] `security/{SecurityConfig,Roles,JwtRoleConverter,AudienceValidator,SeedSecurityProperties,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler,AuthenticatedUserMdcFilter,SecurityContextActor}.java` -- everything in Always for the chain, roles, validation, CORS, headers, actor and MDC.
- [x] `common/actor/*` -- the read accessor for the system scope; one effective `CurrentActor`.
- [x] `common/error/web/GlobalExceptionHandler.java` -- the 401/403 mappings.
- [x] `common/idempotency/*` and `db/migration/common/V202611161600__common_add_idempotency_caller.sql` -- the order and the caller scope.
- [x] `config/OpenApiConfig.java`, application YAMLs, `.env.example`, `backend/openapi/api.yaml` (regenerated) -- docs security and configuration.
- [x] `SPEC.md` -- CAP-7 success line: readiness DOWN when migrations are pending; startup fails when a mapped table is missing (`ddl-auto=validate`), decided 2026-11-16. Also `security.md` §3: Prometheus is open on the network-restricted management port.
- [x] Tests:
  - `fixtures/security/TestJwt` (key pair, builders);
  - `SecurityIT` (no token, bad token, skew, roles, custom claim, CORS, headers, method security);
  - `ManagementSecurityIT`;
  - `DocsSecurityIT` (`local`/`dev`/default);
  - `RouteTableIT` (every handler method in `com.company.seed`: no token → 401);
  - an auditing case, a logging case, an idempotency case.
  - The ArchUnit rule with its fixtures.
  - Existing tests updated to send tokens.
  - Every matrix row is covered.
- [x] `README.md` -- local sign-in with `demo-user`/`demo-admin` from mock-oauth2-server; the management port must be network-restricted.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, `checkOpenApi` passes with the regenerated `api.yaml`, and the module and architecture rules pass.
- Given the compose stack and the `local` profile, when a token for `demo-admin` is fetched from mock-oauth2-server, then `/actuator/loggers` on 8081 answers 200 with it (manual check).

## Implementation Notes

- Planning: one critic pass (15 findings), all resolved by spec edits and two human decisions at checkpoint 1 (docs in `dev`, option a; replays and 401/403/408/429, option a). The user's "a, a, apply" approved the spec and proceeding without a second pass (2026-11-16).

- Implementation (subagent): all tasks done. Choices beyond the spec:
  - `CorrelationIdFilter` is also added inside the security chain, so 401/403 on the management port carry the ID; on the application port it skips its second pass.
  - ERROR/ASYNC re-dispatches pass the URL rules.
  - The extra doc paths `/v3/api-docs`, `/v3/api-docs.yaml` and `/swagger-ui.html` are open.
  - Unexposed management endpoints answer 401 to anonymous callers and 404 to signed-in ones.
  - Logout and the request cache are off, and Boot's generated user is excluded.
  - `SecurityConfig` loads only in a servlet web app, so `migrate` needs no issuer.
  - Startup-failure tests use `WebApplicationContextRunner`.
  - The served (not exported) document adds an OIDC sign-in scheme in `local`/`dev`.
  - `JsonErrorController` gets `@PreAuthorize("permitAll()")`.
  - The tests strip `OIDC_*`, `CORS_ALLOWED_ORIGINS` and `SWAGGER_UI_CLIENT_ID`.
- Not done: both manual checks (Swagger UI PKCE sign-in against mock-oauth2-server, and `demo-admin` → `/actuator/loggers` on the compose stack). The subagent was unsure whether mock-oauth2-server applies the new `seed-swagger` mapping and login-form claims. The client-facing answer when the issuer is unreachable on the first token is untested.
- Verify (main agent): `./gradlew clean build` passes with 426 tests in 49 suites and 0 failures (42 s). The build also passes with `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `OIDC_AUDIENCE` and `CORS_ALLOWED_ORIGINS` exported. `api.yaml` changed only by the 7-line bearer scheme (regenerated). snap-guard reports 0 findings across 81 files.

- Review pass 1 patches P1–P13 were applied by the implementation subagent (re-engaged; its first attempt stopped at an account usage limit before any edit and was resumed after the reset):
  - **Tokens:** `exp` and a clean `sub` (at most 255 characters) are required; Spring's `JwtAudienceValidator` replaces the custom audience validator.
  - **Database:** a `caller` length CHECK.
  - **Issuer:** unreachable issuer → 503; new `IssuerIT` against a stub issuer exercises the production decoder.
  - **Migration:** a not-rolling-compatible warning in the migration header and README.
  - **Logging:** bare `sub` as `user.id` inside `user:` scopes; a shared `SecurityErrorLog` line.
  - **Errors:** anonymous `@PreAuthorize` denial → 401.
  - **CORS:** `JsonCorsProcessor` envelope; strict origin validation.
  - **Roles claim:** exact claim name first; no doubled `ROLE_`.
  - **ArchUnit:** the rule is limited to request-mapped methods.
  - **Startup:** a check on the security filter order.
  - **Tests:** a three-token route table, `user.id` on ASYNC/ERROR lines, anonymous ERROR dispatch, Swagger initializer.
  - **Cleanup:** `swaggerUiClientId` removed; docs.
- Human decision R38 (option a): unexposed management endpoints answer 401 to anonymous callers (frozen text amended; see the Spec Change Log).
- Still not verified: the manual Swagger UI PKCE sign-in against mock-oauth2-server, and the manual `demo-admin` → `/actuator/loggers` check on the compose stack.
- Final verification (main agent): `./gradlew clean build` passes with 453 tests in 50 suites and 0 failures (43 s). The build also passes with `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `OIDC_AUDIENCE` and `CORS_ALLOWED_ORIGINS` exported. `api.yaml` changed only by the bearer scheme. snap-guard: 0 findings across 83 files. Doubt scan: clean. Context lake: none.

## Spec Change Log

- 2026-11-16, review R38: the human approved amending the frozen management-port rule. Unexposed endpoints answer 404 to signed-in callers and 401 to anonymous ones, matching the implementation. Known-bad state avoided: answering 404 to anonymous callers would require opening non-endpoint paths on the management port to everyone. KEEP: the `EndpointRequest` rules as implemented.

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 58 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | security + edge | Tokens with no `exp` are accepted and never expire | high | `JwtTimestampValidator` in 7.1.2 defaults `allowEmptyExpiryClaim = true` (bytecode checked by the security layer); no validator requires `exp`. | patch (P1) |
| R2 | security + blind + edge | Tokens with no `sub` are accepted and their callers all become `anonymous`: shared idempotency records (cross-caller replay), `anonymous` audit rows; the `seed-swagger` mock mapping sets no `sub` | high | `SecurityContextActor.id()` falls back to `anonymous`; nothing rejects a missing `sub`. | patch (P1) |
| R3 | security + blind + edge | `sub` reaches the MDC, audit columns and the idempotency `caller` without cleaning or length limit; `caller` has no length CHECK (a long `sub` can break the unique index) | medium | `AuthenticatedUserMdcFilter` puts the raw `sub`; `SystemActor.requireValid` rules are not applied; the migration has no CHECK. | patch (P1, P2) |
| R4 | blind + edge + verif-gap | Issuer or JWKS unreachable on the first token: the answer is undefined (likely 500); the production decoder's validator wiring is never exercised | medium | `SupplierJwtDecoder` failures become `AuthenticationServiceException`, which the failure handler rethrows; every web test uses `TestSecurity`'s decoder. | patch (P3) |
| R5 | blind + edge | Caller migration breaks old instances during a rolling deploy (drops the constraint they name; `caller NOT NULL` they do not supply) | medium | data-and-migrations.md §11.3 asks for expand/contract. The seed has no shared deployment yet, so the cheapest honest fix is to document the stop-before-migrate requirement for this release. | patch (P4) |
| R6 | blind | `user.id` holds bare `sub` in requests but `user:<sub>` inside scopes | medium | errors-and-logging.md §4: `user.id` is the subject or `system:*`. | patch (P5) |
| R7 | blind | `@PreAuthorize` denial for an unauthenticated caller answers 403, not 401 | medium | The advice handles `AccessDeniedException` before `ExceptionTranslationFilter`, which would send 401 plus the challenge for anonymous callers. | patch (P6) |
| R8 | edge | CORS rejections are 403 plain text, not the envelope | medium | `DefaultCorsProcessor.rejectRequest` writes "Invalid CORS request" directly. | patch (P7) |
| R9 | blind + edge + security | CORS origins barely validated (path, trailing slash, `null` accepted) | low | Only `*` is rejected; a malformed origin silently never matches. A direct check. | patch (P7) |
| R10 | edge | URL-namespaced role claims (`https://x/roles`) unreachable; `ROLE_`-prefixed entries become `ROLE_ROLE_…` | medium | The dotted path always splits; the prefix is always prepended. | patch (P8) |
| R11 | edge | ArchUnit `@PreAuthorize` rule would also demand it on public `@InitBinder`/`@ExceptionHandler` methods | low | The rule targets public methods, not request-mapped ones; future controllers would fail falsely. A direct narrowing. | patch (P9) |
| R12 | blind | `IdempotencyFilter.ORDER` uses the default security order, not the configured `spring.security.filter.order` | medium | Changing that property silently puts idempotency before authentication again; a startup check is direct. | patch (P10) |
| R13 | blind | `RouteTableIT` covers only the no-token case | medium | security.md §2: no token, role-less token and valid token per route. | patch (P11) |
| R14 | verif-gap | `user.id` on ASYNC/ERROR re-dispatch lines untested | medium | Pre-verified. | patch (P11) |
| R15 | verif-gap | Anonymous ERROR-dispatch `permitAll` untested | medium | Pre-verified: every ERROR-dispatch test carries a token. | patch (P11) |
| R16 | verif-gap | Swagger UI client id and PKCE never checked | medium | Pre-verified: no test reads the served initializer. | patch (P11) |
| R17 | verif-gap | Caller migration only tested on a fresh schema | low | Script is correct as written; Flyway checksums freeze it; filed disposition defer. | rejected |
| R18 | slop + blind | 401/403 log lines copy the handler's format inline; the two 403 paths resolve the subject differently (`subject=null` vs `anonymous`); URI logged raw | low | Three copies of one format; a shared helper is direct. | patch (P12) |
| R19 | slop | `AudienceValidator` re-implements `JwtAudienceValidator` | low | Spring Security 7.1.2 ships the same check. | patch (P12) |
| R20 | slop | `swaggerUiClientId` record field exists only to be forwarded | low | No production code reads it. | patch (P12) |
| R21 | slop | Hand-built issuer decoder duplicates Boot's | low | Kept deliberately: one validator bean shared with the tests and a 30 s skew (Boot's is 60 s and conditional). | rejected |
| R22 | security | Custom validator drops Spring's default `typ`/x5t validators; no algorithm pinned | low | The audience check separates access tokens; a strict `typ` check would break IdPs that send `at+jwt` or custom types; no mTLS-bound tokens are used. | rejected |
| R23 | security | Replay skips `@PreAuthorize` | false | Decided at checkpoint 1 (option a) and documented. | rejected |
| R24 | security | Docs public on the app port in every profile | false | Decided at checkpoint 1; springdoc answers 404 where disabled (tested). | rejected |
| R25 | security | Prometheus open | false | Decided (option a); security.md §3 and the README require network restriction. | rejected |
| R26 | security | `loggers` lets ADMIN change log levels | false | Required by the deferred story-5 item and errors-and-logging.md §4. | rejected |
| R27 | security | Issuer over plain HTTP not prevented | low | `local` uses http by design; security.md §6 requires HTTPS when deployed; a check would add profile rules. | rejected |
| R28 | security | Case-folded roles widen privilege | false | Upper-casing was decided at checkpoint 1. | rejected |
| R29 | security | No HSTS behind a TLS proxy | false | security.md §6: HSTS at the edge. | rejected |
| R30 | security | A `SystemActor` `user:` scope can override the request's user | low | By design for async work on a user's behalf; no request code opens one. | rejected |
| R31 | security | Unauthenticated requests write an INFO line each (log flooding) | low | Rate limiting is story 7b; one line per rejected request is the logging standard. | rejected |
| R32 | security | ArchUnit rule misses RouterFunction/Data REST; `permitAll()` satisfies it | low | Neither style is used; the rule proves a decision was written, by design. | rejected |
| R33 | security | Mock IdP accepts any secret and claims | false | Local-only tool, documented as such. | rejected |
| R34 | blind | MDC cleared outside `finally` keeps `user.id` on the thread after an escaped exception | low | Story 5's deliberate trade-off (the container's ERROR line keeps the ID); the next dispatch clears it at start. | rejected |
| R35 | blind | Issuer trailing slash not normalised | low | Some IdPs (Auth0) issue `iss` with a trailing slash; normalising would break them. | rejected |
| R36 | blind | Swagger UI sign-in unverified; README misses redirect-URI and issuer-CORS notes for a real IdP | low | Docs fix is direct; the UI wiring is covered by R16. | patch (P13) |
| R37 | blind | security.md §5 lacks `SWAGGER_UI_CLIENT_ID`; §2 docs wording outdated | low | Direct doc corrections. | patch (P13) |
| R38 | blind | Unexposed management endpoints answer 401 to anonymous callers, while the frozen spec says 404 | low | A deviation from the frozen Always text; stricter (no endpoint enumeration). | resolved by the human (option a, 2026-11-16): keep 401; frozen text amended |
| R39 | edge (claim) | `caller` collapses token-less-`sub` callers to `anonymous` | high | Same root as R2. | patch (P1) |
| R40 | verif-gap (other) | `runsAfterTheSecurityFilterChain` only checks a constant | false | `IdempotencySecurityIT` would fail if the order were wrong (layer's own note). | rejected |

## Design Notes

**Docs in `dev` (resolved at checkpoint 1):** Swagger UI fetches `/v3/api-docs` the moment the page loads, before the user can press Authorize, so a protected spec could never load. The contract stays readable, and the data behind it is protected per call.

**Why `IdempotencyFilter` moves to order −90:** filters ordered after the security chain run inside its scope, with the security context set. Replays therefore pass authentication, and the caller is known for scoping.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `checkOpenApi` green after `updateOpenApi`.

**Manual checks (if no CLI):**
- In `dev`-like `local`, open Swagger UI, press Authorize, sign in as `demo-user` at the mock issuer (PKCE): the UI holds a token, and "Try it out" calls succeed with it while calls without it answer 401.
- `docker compose -f infrastructure/compose.yaml --env-file .env up -d`, `./gradlew bootRun --args='--spring.profiles.active=local'`, get a `demo-admin` token from `http://localhost:8090/seed/token` (client credentials, `client_id=demo-admin`), then `curl -H "Authorization: Bearer <t>" :8081/actuator/loggers` → 200.

## Tech Spec Critic Findings

- [SOLVED] [high] check-1 | spec line 63 | The user decided that in `dev` both `/v3/api-docs` and Swagger UI require a signed-in user, but the spec leaves Swagger UI `permitAll` in `dev` (and expects anonymous Swagger UI → 200), and the Design Notes admit the setup leaves the UI unusable in `dev`, so the decision is contradicted and unresolved.
- [SOLVED] [high] check-1 | spec line 59 | The intent requires that 401/403/429 answers are never stored for 24 h, but `IdempotencyFilter` stores every 4xx, and a `@PreAuthorize` 403 is produced downstream of the order −90 filter, so it would be stored and replayed; neither the spec nor the Idempotency matrix row excludes it.
- [SOLVED] [high] check-6 | spec line 116 | `## Things this relies on` omits ArchUnit (new rule), Spring Modulith (closed `security` module), Flyway (new migration), Testcontainers/Docker (ITs) and SLF4J MDC with Logback ECS structured logging (`user.id`).
- [SOLVED] [medium] check-6 | spec line 116 | `## Things this relies on` omits `management.server.port`, `management.endpoint.health.show-details`/`roles`, `management.endpoints.web.exposure.include` (to expose `loggers`), the per-profile springdoc enable flags, and the Gradle `updateOpenApi`/`checkOpenApi` tasks.
- [SOLVED] [medium] check-1 | spec line 57 | The deferred item says `user.id` is the token subject or `system:*` for background work, but the spec only sets `user.id = sub` after authentication; `SystemActor` work gets no `user.id` and no matrix row covers it.
- [SOLVED] [medium] check-1 | spec line 44 | The deferred item denies anonymous access to every Actuator endpoint except liveness and readiness (plus Prometheus by decision), but the spec also makes the health root `permitAll` without saying why.
- [SOLVED] [medium] check-5 | spec line 44 | "anything else is denied" on the management port reads as `denyAll` (even ADMIN gets 403) or "requires ADMIN"; the readings give ADMIN different results and no matrix row decides it.
- [SOLVED] [medium] check-5 | spec line 41 | "the claim named by `OIDC_ROLES_CLAIM`" reads as a top-level claim only or a path to a nested claim (Keycloak's `realm_access.roles`); under the first, such issuers' tokens carry no roles.
- [SOLVED] [medium] check-5 | spec line 41 | "maps each entry to `ROLE_<NAME>`" does not say whether entries are upper-cased, so `admin` maps to `ROLE_ADMIN` or `ROLE_admin`.
- [SOLVED] [medium] check-3 | spec line 64 | Swagger UI sign-in via Authorize with OIDC + PKCE has no matrix row stating what the developer sees.
- [SOLVED] [medium] check-3 | spec line 96 | No matrix row covers a replay by the same authenticated caller (stored response with `Idempotent-Replayed` and security headers) or by a caller who has since lost the role.
- [SOLVED] [low] check-3 | spec line 67 | "A missing audience or issuer fails startup" has no matrix row stating what the operator sees.
- [SOLVED] [low] check-5 | spec line 60 | The `caller text NOT NULL` migration does not say what existing `idempotency_record` rows get.
- [SOLVED] [low] check-2 | spec line 97 | The 403 rows do not say the envelope carries the correlation ID, unlike the 401 row.
- [SOLVED] [low] check-5 | spec line 85 | The value of `user.id` on log lines of unauthenticated or rejected requests is unspecified.
