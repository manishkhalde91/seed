---
title: 'Story 7b — Request limits: body size and per-user write rate'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_commit: 'ddce8809d2c5cd536a388de44bb1a70a1b0991d7'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/security.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** No request size limit exists outside keyed idempotent writes, and nothing stops one caller from flooding writes. security.md §6 requires JSON bodies of at most 1 MB and 60 writes per minute per user, both configurable.

**Approach:** The deferred slice 7b of story 7.
- A global body-size filter answers 413 `PAYLOAD_TOO_LARGE`. It replaces the request cap inside `IdempotencyFilter`, which keeps only its stored-response cap.
- Servlet multipart limits also answer 413.
- An in-memory, per-instance token-bucket write limiter answers 429 `RATE_LIMITED` with `Retry-After`.
- Decisions (2026-11-16):
  - counters are kept in memory on each instance, behind an interface (option a);
  - multipart allows 10 MB per file and 10 MB per request (option a).

## Boundaries & Constraints

**Always:**
- **Body size** (`common.limits.RequestSizeFilter`, ordered right after `RequestLoggingFilter`, so before authentication):
  - Applies to every request whose content type is not `multipart/*`.
  - A `Content-Length` over `seed.limits.max-body-size` (default `1MB`, Spring `DataSize`, so 1,048,576 bytes) → 413 `PAYLOAD_TOO_LARGE` envelope, before anything else reads the body. The message names the limit: "Request body exceeds 1MB".
  - Without a length, the input stream is wrapped so reading past the limit throws `PayloadTooLargeException`. This exception, anywhere in a cause chain, is answered 413 by `GlobalExceptionHandler` and by `JsonErrorController`.
  - `server.tomcat.max-http-form-post-size` is set to the same limit.
- **Multipart:** `spring.servlet.multipart.max-file-size` and `max-request-size` default to `10MB` through `SEED_MAX_UPLOAD_SIZE`. A larger upload → 413 through the existing `MaxUploadSizeExceededException` mapping. `server.tomcat.max-swallow-size` equals the upload limit, so a refused upload still receives its 413 instead of a connection reset (decided at implementation verify, 2026-11-16, option a).
- **Write rate** (`common.limits.WriteRateLimitFilter`):
  - **Placement:** ordered after the security chain and before `IdempotencyFilter`.
  - **What counts:** `POST`, `PUT`, `PATCH` and `DELETE`, keyed by `CurrentActor.id()`. Other methods are never limited. Idempotent replays count, because they pass this filter first.
  - **Oversized writes:** one rejected through its `Content-Length` costs no token (it is refused before the limiter). An oversized chunked body is only detected while it is read, after the limiter, so it costs one token. This is accepted and documented.
  - **Budget:** a token bucket per caller, capacity `seed.limits.writes-per-minute` (default 60), refilled continuously at that rate per minute, timed by the application `Clock`.
  - **Over budget:** 429 `RATE_LIMITED` envelope plus `Retry-After` = seconds until one token is available, rounded UP to a whole second (at least 1), logged at INFO with the caller run through `LogSafe`.
  - **Storage:** buckets live behind a `RateLimiter` interface. The in-memory implementation drops buckets idle longer than one refill window (`@Scheduled`, every minute).
  - **Per-instance limit:** the limit applies on each instance separately (N instances allow up to N × the limit). This is documented in security.md §6 and the README.
- **Filter order check:** the 7a startup check is extended. Startup fails unless the security filter order is lower than `WriteRateLimitFilter.ORDER`, and that is lower than `IdempotencyFilter.ORDER`.
- **Service-level exception:** `RateLimitExceededException` (`common.error`, a `SeedException` with status 429, code `RATE_LIMITED` and a `retryAfter` `Duration`) lets services signal the same thing. `GlobalExceptionHandler` answers it with `Retry-After`, logged at INFO.
- **Idempotency change:** `IdempotencyFilter` no longer answers 413 itself. Its property becomes `seed.idempotency.max-stored-response-size` (default `1MB`); larger responses are sent but not stored, as before. api-standards.md §1 and the README are updated.
- **Configuration:** `seed.limits.*` is a validated `@ConfigurationProperties` record: a positive size below 2 GB, writes-per-minute ≥ 1. The variables `SEED_MAX_BODY_SIZE`, `SEED_WRITES_PER_MINUTE` and `SEED_MAX_UPLOAD_SIZE` are listed in `.env.example`.

**Never:**
- A shared or distributed counter store (Redis, PostgreSQL).
- Limits on reads.
- Per-endpoint or per-role limits.
- `RateLimit-*` informational headers.
- Changing the limits of keyed idempotent writes beyond the global one.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Exactly at limit | JSON `POST` of exactly 1,048,576 bytes, with a token | handled normally | N/A |
| Over limit, length known | `Content-Length` 1,048,577, with and without a token | 413 `PAYLOAD_TOO_LARGE` envelope with the correlation ID and the message "Request body exceeds 1MB"; the handler does not run | INFO |
| Over limit, chunked | chunked body of 1 MB + 1, plain and with an `Idempotency-Key` | 413 envelope; no idempotency row | INFO |
| Multipart | a 10 MB + 1 file; a 1 KB file | 413 envelope; handled normally (the 1 MB JSON limit does not apply) | INFO |
| Rate under budget | 60 writes from caller A within one minute (fixed clock) | all reach the handler | N/A |
| Rate over budget | 61st write from A; then the clock advances 1 s; at 5/min, a 6th write 500 ms and 1 s after the 5th | 429 `RATE_LIMITED` with `Retry-After: 1`; then one more write passes; 429 with `Retry-After: 12` (rounded up from 11.5 s) and `Retry-After: 11` (exactly 11 s) | INFO |
| Isolation | caller B writes while A is exhausted; A sends `GET`s | B passes; A's `GET`s pass | N/A |
| Replay counts | caller with 1 token left sends a keyed write, then replays it | first is 201; the replay is 429 | N/A |
| Configurable | `seed.limits.writes-per-minute=5`, `max-body-size=2KB`, `SEED_MAX_UPLOAD_SIZE=1KB` | the 6th write is 429; a 2 KB + 1 body is 413; a 1 KB + 1 multipart file is 413 | N/A |
| Service exception | a probe service throws `RateLimitExceededException(Duration.ofSeconds(7))` | 429 `RATE_LIMITED` with `Retry-After: 7` | INFO |
| Eviction | a bucket idle for more than a minute, then the cleanup runs | the bucket is removed; the next write starts with a full bucket | N/A |
| Order check | security filter order configured at or above `WriteRateLimitFilter.ORDER` | startup fails naming both orders | N/A |
| Stored-response cap | keyed write whose response exceeds `max-stored-response-size` | response sent, not stored (unchanged behaviour, renamed property) | WARN |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/idempotency/{IdempotencyFilter,IdempotencyProperties}.java` -- `maxBodySize` (properties line ~24) drives both the 413 and the stored-response cap; `readNBytes(limit + 1)` in the filter. Remove the 413 path and rename the property.
- `backend/src/main/java/com/company/seed/common/logging/{CorrelationIdFilter,RequestLoggingFilter}.java` -- `ORDER` constants (`HIGHEST_PRECEDENCE + 10`, `+ 11`); the size filter goes at `+ 12`.
- `backend/src/main/java/com/company/seed/common/idempotency/IdempotencyFilter.java:97` -- `ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10` (−90); the rate limiter sits between −100 and −90 (e.g. −95).
- `backend/src/main/java/com/company/seed/security/SecurityConfig.java:246` -- the existing order check to extend.
- `backend/src/main/java/com/company/seed/common/error/web/{GlobalExceptionHandler,JsonErrorController,ApiErrorResponseWriter,SafeMessages}.java` -- the `MaxUploadSizeExceededException` → 413 mapping exists; add `PayloadTooLargeException` (cause chain) and `RateLimitExceededException` with `Retry-After`.
- `backend/src/main/java/com/company/seed/common/actor/CurrentActor.java` -- the caller key.
- `backend/src/main/java/com/company/seed/config/SchedulingConfig.java` -- scheduling exists (`!migrate`), for bucket eviction.
- `backend/src/test/java/com/company/fixtures/{web,security,idempotency}/*` -- probe controllers, `TestJwt`, `LenientRestClient` (sends a `USER` token), mutable clock patterns.

## Things this relies on

- `ServletInputStream` wrapping (`HttpServletRequestWrapper`), `OncePerRequestFilter` -- Jakarta Servlet 6.1 / Spring Framework 7
- `spring.servlet.multipart.{max-file-size,max-request-size}`, `server.tomcat.max-http-form-post-size`, `MaxUploadSizeExceededException` -- Spring Boot 4.1.2 / Spring Framework 7
- `@ConfigurationProperties` records with `DataSize`, `@Scheduled` -- Spring Boot 4.1.2 / Spring Framework 7
- `SecurityFilterProperties.DEFAULT_FILTER_ORDER`, `spring.security.filter.order` -- Spring Boot 4.1.2 (used in 7a)
- `java.time.Clock` -- JDK 21 (the `ClockConfig` bean)
- Token-bucket rate limiting (continuous refill) -- the named algorithm, hand-written in `InMemoryRateLimiter`
- Jakarta Bean Validation (`@Validated`, Hibernate Validator) -- `spring-boot-starter-validation`, already a dependency
- Testcontainers + Docker for the ITs -- already in the build

## Tasks & Acceptance

**Execution:**
- [x] `common/limits/{LimitsProperties,RequestSizeFilter,PayloadTooLargeException,RateLimiter,InMemoryRateLimiter,WriteRateLimitFilter}.java` -- everything in Always for size and rate, including eviction.
- [x] `common/error/RateLimitExceededException.java`; `common/error/web/{GlobalExceptionHandler,JsonErrorController}.java` -- the 413 (cause chain) and 429 mappings.
- [x] `common/idempotency/{IdempotencyFilter,IdempotencyProperties}.java` -- drop the 413 path; rename to `max-stored-response-size`; adjust its tests.
- [x] `security/SecurityConfig.java` -- the extended order check.
- [x] `application.yml`, `.env.example` -- `seed.limits.*`, multipart and Tomcat form limits, all through the environment variables.
- [x] `security.md` §6, `api-standards.md` §1, `README.md` -- limits, per-instance rate note, renamed property.
- [x] Tests:
  - `RequestSizeIT`;
  - `WriteRateLimitIT` (mutable clock, `TestJwt` callers);
  - `InMemoryRateLimiterTest` (bucket maths, `Retry-After`, eviction);
  - `LimitsPropertiesTest`;
  - an order-check case in `SecurityConfigurationTest`;
  - a `RateLimitExceededException` row in `GlobalExceptionHandlerTest`.
  - Every matrix row is covered.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, `api.yaml` is unchanged, and the architecture and module rules pass.

## Implementation Notes

- Planning: one critic pass (7 findings), all resolved by spec edits the user approved. The user's "apply your recommendation" included proceeding without a second pass (2026-11-16).

- Implementation (subagent): all tasks done. Choices beyond the spec:
  - `RequestSizeFilter` also answers an oversize thrown later in the chain (e.g. while `IdempotencyFilter` reads a keyed chunked body) with 413 at INFO, so Tomcat never logs a client error at ERROR;
  - a shared `common/web/RequestCharsets` and a separate `LimitedBodyRequest` (to clear snap-guard duplication);
  - the test config sets `seed.limits.writes-per-minute: 1000000`, so existing single-user tests on a fixed clock are not throttled.
- Main agent, in Verify: the subagent found the 5/min `Retry-After` row arithmetically wrong (11, not 12). The human approved correcting it, and setting `server.tomcat.max-swallow-size` to the upload limit (option a for both). I made the YAML change and removed `RequestSizeIT`'s `max-swallow-size=-1` workaround; `RequestSizeIT` (9) and `ConfiguredLimitsIT` (6) pass with the production setting.
- Known limit: anonymous writes would share the `anonymous` bucket (today they are refused with 401 first).
- Verify: `./gradlew clean build` passes with 520 tests in 55 suites and 0 failures (before the swallow-size edit); `api.yaml` is unchanged; snap-guard reports 0 findings.

- Review pass 1 patches P1–P10 were applied by the implementation subagent (re-engaged):
  - **Limits:** only `multipart/form-data` skips the body limit; keyed `multipart/mixed` is covered; oversized form posts get 413 through Tomcat's `InvalidParameterException`; the upload 413 names its limit; the form-post limit follows `seed.limits.max-body-size`.
  - **Browser policy:** CORS and the three security headers moved into a `security/BrowserPolicyFilter` before the size filter, so early 413s carry them.
  - **Tests:** the production default of 60 is tested; idempotency size settings are separated in tests.
  - **Cleanup:** `LogSafe` on logged URIs; shared `WriteMethods` and `Causes.findCause`; `LimitsProperties` validates in its constructor; docs.
- Departures it made:
  - `BrowserPolicyFilter` is at `RequestLoggingFilter.ORDER + 1` and `RequestSizeFilter` at `+ 2`, so CORS refusals keep their correlation ID and summary line; the size filter still runs before authentication, which is what the frozen rule protects;
  - the chain keeps writing the headers for the management port;
  - `RequestSizeFilter` imports Tomcat's `InvalidParameterException` (container coupling: a switch to another servlet container must replace that check).
- Final verification (main agent): `./gradlew clean build` passes with 534 tests in 55 suites and 0 failures (47 s). The build also passes with `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `SEED_WRITES_PER_MINUTE`, `SEED_MAX_BODY_SIZE` and `OIDC_AUDIENCE` exported. `api.yaml` is unchanged. snap-guard: 0 findings across 41 files. Doubt scan: clean. Context lake: none.

## Spec Change Log

- 2026-11-16, implementation verify: the human approved two frozen-intent amendments. (1) The 5/min `Retry-After` row was arithmetically wrong: 1 s after the 5th write the wait is exactly 11 s, so the answer is `11`; the row now lists 500 ms → 12 and 1 s → 11. (2) Tomcat's `max-swallow-size` is set to the upload limit, because the 2 MB default reset connections and lost the 413 of large refused uploads. KEEP: the round-up rule and the code as implemented.

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 42 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | verif-gap + security + blind + edge | Keyed `multipart/mixed`/`related` writes bypass every size limit: `IdempotencyFilter.readAllBytes()` buffers them unbounded (regression from 6c's 1 MB cap) | high | `RequestSizeFilter` skips every `multipart/*`; `IdempotencyFilter.isForm` exempts only `multipart/form-data`; the 6c cap was removed. | patch (P1) |
| R2 | blind | Early 413 (before the security chain) carries no CORS or security headers, so a cross-origin browser sees an opaque error | medium | `CorsFilter` and `HeaderWriterFilter` live inside the chain, which runs after `RequestSizeFilter`. | patch (P2) |
| R3 | blind + edge | Chunked form-urlencoded posts over the limit silently lose their parameters instead of 413 | medium | Tomcat parses forms itself under `max-http-form-post-size` and flags the parse failure; the wrapper never sees the bytes. | patch (P3) |
| R4 | blind + edge | A file of exactly the upload limit is refused (request limit = file limit, plus multipart overhead) | low | Consequence of the decided "10 MB per file and per request"; the docs promise the file size, so they need to say the limit includes overhead. | patch (P4, docs) |
| R5 | verif-gap | Production default 60/min untested | medium | Pre-verified: the test config overrides it everywhere and `WriteRateLimitIT` pins 60 itself. | patch (P5) |
| R6 | security + blind | New filters log the raw request URI (before authentication for 413) | low | `RequestLoggingFilter` logs the route template; the new lines skip `LogSafe`. | patch (P6) |
| R7 | slop | Write-method set duplicated from `IdempotencyFilter` | low | Two copies of "a write" can drift. | patch (P7) |
| R8 | slop | Three hand-written cause-chain loops | low | One generic finder serves all. | patch (P7) |
| R9 | slop + blind + edge | `LimitsProperties` uses Bean Validation unlike the other property records; direct construction gives an NPE at request time; the limiter repeats the check; `@EnableConfigurationProperties` twice | low | The other records validate in their compact constructor. | patch (P8) |
| R10 | blind | Upload 413 says "Payload too large" while the body 413 names the limit | low | Inconsistent messages for one documented behaviour. | patch (P9) |
| R11 | edge | Tomcat form-post limit reads `SEED_MAX_BODY_SIZE`, not `seed.limits.max-body-size` | low | Setting the property directly lets the two diverge. | patch (P9) |
| R12 | edge | `max-body-size` near 2 GB makes `readAllBytes` throw `OutOfMemoryError` | low | Bound is `< Integer.MAX_VALUE`; Java arrays cap slightly lower. | patch (P8) |
| R13 | blind | Idempotency ITs set both size settings to one value; a renamed unit test no longer tests what its name says | low | No IT can separate the two limits. | patch (P10) |
| R14 | slop | Restating Javadocs | low | Direct deletions. | patch (P10) |
| R15 | security + blind | `max-swallow-size` = 10 MB lets early-rejected requests (including anonymous 401s) drain up to 10 MB; a declared length above it still resets | low | Decided by the human at implementation verify (option a), with this cost stated; the docs gain the reset note. | rejected (decided; note in P4) |
| R16 | slop | `RateLimitExceededException` handler unused by production code | false | Required by the frozen spec (errors-and-logging.md §2 row). | rejected |
| R17 | slop | `RateLimiter` interface has one implementation | false | Decided: "behind an interface" (option a). | rejected |
| R18 | security + blind + edge | Anonymous writes share one bucket | low | No `permitAll` write exists; documented known limit. | rejected |
| R19 | security | Requests failing authentication are not rate limited | false | The spec limits per caller after authentication by design. | rejected |
| R20 | security | Per-instance counters | false | Decided (option a), documented. | rejected |
| R21 | security | `PayloadTooLargeException extends IOException` could be swallowed by a future reader | low | No code does so; robustness note only. | rejected |
| R22 | blind | Spec missing from the review diff | false | Deliberately excluded; the edge layer receives it as the claims file. | rejected |
| R23 | blind | `IdempotencyFilter` has no own cap and no order check against `RequestSizeFilter` | low | After P1 every keyed body passes the size filter; both orders are compile-time constants. | rejected |
| R24 | blind | Property rename has no deprecation metadata | low | The seed has never been deployed with the old key. | rejected |
| R25 | blind | Bucket map has no size cap; eviction scans the whole map | low | Bounded by authenticated subjects, pruned every minute. | rejected |
| R26 | blind | No metrics for 413/429 | low | Not in quality-and-delivery.md §10's metric list. | rejected |
| R27 | blind | `LimitedBodyRequest` allows both `getReader()` and `getInputStream()` | low | Servlet-contract nicety; no caller mixes them. | rejected |
| R28 | edge | Clock stepped back can evict a non-full bucket early | low | Requires the system clock to go backwards past a minute. | rejected |

## Design Notes

**Why size before authentication and rate after it:** the size check needs no identity and protects the token parser and everything after it from huge bodies. The rate limit needs the caller, so it must run inside the security scope. It still runs before idempotency, so retried writes count, which is what the 6c deferral asked for.

**Why a token bucket:** it allows a short burst up to the full budget, refills smoothly, and gives an exact `Retry-After`. A fixed window lets a caller send twice the limit across a window boundary.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `api.yaml` unchanged.

## Tech Spec Critic Findings

- [SOLVED] [medium] check-6 | spec line 88 | The token-bucket algorithm relied on by Intent, Boundaries and Design Notes is a named algorithm missing from `## Things this relies on`.
- [SOLVED] [medium] check-6 | spec line 88 | The "validated" `@ConfigurationProperties` record relies on Jakarta Bean Validation (`@Validated` plus a validator), which is not listed.
- [SOLVED] [medium] check-5 | spec line 34 | "default `1MB`" and the matrix's "exactly 1 MB" / "1 MB + 1" read as 1,000,000 bytes or 1,048,576 bytes (Spring `DataSize` parses "1MB" as binary); a body between the two is accepted under one reading and 413 under the other.
- [SOLVED] [medium] check-5 | spec line 42 | "`Retry-After` = whole seconds until one token is available" does not say whether the value rounds down or up; rounding down gives too short a wait at non-default rates (5/min = 12 s per token) and the obedient client gets another 429.
- [SOLVED] [low] check-3 | spec line 69 | No matrix row shows that changing `SEED_MAX_UPLOAD_SIZE` changes the multipart 413 threshold.
- [SOLVED] [low] check-5 | spec line 40 | Whether a write rejected with 413 consumes a rate-limit token is unspecified; an oversized `Content-Length` is rejected before the limiter (no token), but an oversized chunked body is detected after it (a token spent).
- [SOLVED] [low] check-2 | spec line 34 | The 413 envelope's content beyond code and correlation ID is unstated, so the caller may not learn the limit it exceeded.
