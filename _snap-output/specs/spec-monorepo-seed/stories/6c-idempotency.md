---
title: 'Story 6c — Idempotency-Key support'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_commit: 'c68588e4a880d25b3c3153cae5e40bf3ea49941c'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/data-and-migrations.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A client that retries a write (for example after a timeout) may run it twice. api-standards.md §1 promises an `Idempotency-Key` header that prevents this, but nothing implements it.

**Approach:** The deferred slice 6c of story 6. It adds:
- a servlet filter in `common.idempotency`;
- the `common.idempotency_record` table behind a Repository-Pattern repository;
- an hourly cleanup.

Decisions (2026-11-16):
- A concurrent duplicate gets 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` (option a).
- 2xx and 4xx outcomes are stored; a 5xx releases the key (option a).
- The cleanup is a plain `@Scheduled` job now; story 9 moves it into `ScheduledJob` (option a).

## Boundaries & Constraints

**Always:**
- **Scope:** only `POST`, `PUT`, `PATCH` and `DELETE` carrying the header take part. Other methods, and writes without the header, pass through untouched with no record.
- **Header format:** valid when it matches `[A-Za-z0-9._:-]{1,64}`. Anything else is answered 400 `MALFORMED_REQUEST` ("Invalid Idempotency-Key header") before the handler runs.
- **Fingerprint:** SHA-256, hex, of method + `\n` + the raw request URI as received (`getRequestURI()`: still encoded, context path included) + `\n` + the raw query + `\n` + the raw body bytes. The body is read once and re-served to the handler.
- **Claiming a key:** an atomic insert of an `IN_PROGRESS` row carrying a fresh random `claim_token` (`INSERT … ON CONFLICT DO NOTHING`), committed before the handler runs. Then:
  - **Key exists, `COMPLETED`, same fingerprint:** replay the stored status, `Content-Type`, `Location` and body bytes, plus `Idempotent-Replayed: true`. The handler does not run.
  - **Key exists, any state, different fingerprint:** 409 `IDEMPOTENCY_KEY_REUSED`.
  - **Key exists, `IN_PROGRESS`, same fingerprint:**
    - younger than `seed.idempotency.abandon-after` (default 5 min, which must exceed the longest write): 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` ("A request with this Idempotency-Key is in progress; retry later") with `Retry-After: 1`;
    - older: taken over by a conditional update that installs a new `claim_token`, and the handler runs. A write running longer than `abandon-after` can therefore run twice; this is the accepted trade-off that lets clients recover after a crash.
- **After the handler:** both writes match on the key AND this request's `claim_token`, so a request that lost its claim to a takeover changes nothing.
  - A 2xx or 4xx response is stored and the row becomes `COMPLETED`.
  - A 5xx response, or an exception escaping the chain, deletes the row, so a retry runs again.
- **Filter placement:** ordered right after `RequestLoggingFilter`, so the correlation ID and the summary line cover replays and its own 400/409 answers, which `ApiErrorResponseWriter` writes as the error envelope. The replay keeps the original body, including its `correlationId`. The response header carries the current request's ID.
- **Logging:** DEBUG for a malformed key; INFO for the 409s and replays (key run through `LogSafe`); never the body.
- **Table `common.idempotency_record`** (data-and-migrations.md §2–§3):
  - columns: `id bigint GENERATED ALWAYS AS IDENTITY`, `idempotency_key text`, `request_hash text`, `claim_token uuid`, `status text`, `response_status int`, `response_content_type text`, `response_location text`, `response_body bytea`, `created_at timestamptz`, `updated_at timestamptz`;
  - constraints: `uq_idempotency_record__idempotency_key`, a `CHECK` on `status`;
  - index: `ix_idempotency_record__created_at`.
- **Repository:** follows backend-architecture.md §5.
  - A plain-Java `IdempotencyRecordRepository` in `common.idempotency`.
  - A JPA implementation, entity and Spring Data interface in `common.idempotency.infrastructure.persistence`.
  - Retention deletes go through the implementation (native SQL), not a Spring Data `delete*`/`remove*`, so the no-delete ArchUnit rule stays unchanged.
  - This settles story 6 review item R19 (the deferred "exempt the cleanup" entry) without an exemption: none is needed.
- **Cleanup:**
  - `@Scheduled(cron = "${seed.idempotency.cleanup-cron:0 0 * * * *}")` deletes rows whose `created_at` is older than `seed.idempotency.retention` (default 24 h, from the `Clock`).
  - It runs inside `SystemActor.run("system:job:idempotency-cleanup", …)` and logs the deleted count at INFO.
  - Scheduling is enabled by `config/SchedulingConfig`, `@Profile("!migrate")`.
- **New error code and header:** `ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS`, added to api-standards.md §1–§2; the `Idempotent-Replayed` response header goes into the §8 header table.

**Never:**
- Scoping keys to the caller (story 7, deferred).
- The `ScheduledJob` advisory-lock wrapper and job metrics (story 9).
- Storing response headers beyond `Content-Type` and `Location`.
- A Spring Data `delete*`/`remove*` method.
- Logging request or response bodies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No key / not a write | `POST` without the header; `GET` with a key | handler runs normally; no row written | N/A |
| Malformed key | 65 characters; `bad key!` | 400 `MALFORMED_REQUEST` envelope; handler not invoked; no row | DEBUG |
| First write | `POST` with key `k1` | handler runs once; 201 as usual; row `COMPLETED` with status, content type, `Location` and body | N/A |
| Replay | same key, method, path, query and body again, with `X-Correlation-Id: second` | same status, `Location`, `Content-Type` and identical body bytes (body keeps the first request's `correlationId`), plus `Idempotent-Replayed: true` and response header `X-Correlation-Id: second`; handler count still 1 | INFO |
| Reuse | same key with a different body; same key on a different path | 409 `IDEMPOTENCY_KEY_REUSED`; handler not invoked | INFO |
| In progress | second request with the same key while the first is blocked in the handler; and one with a different body | 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` with `Retry-After: 1`; 409 `IDEMPOTENCY_KEY_REUSED`; the first completes normally | INFO |
| Abandoned | an `IN_PROGRESS` row older than 5 min (clock advanced), same fingerprint; then the original claim tries to complete | the request takes the key over; handler runs; row `COMPLETED` with its response; the original claim's complete or release changes nothing | N/A |
| 4xx stored | a keyed `POST` failing validation (400), then repeated | second response is the replayed 400 with `Idempotent-Replayed: true`; handler invoked once | N/A |
| 5xx released | a keyed `POST` whose handler throws (500), then repeated | no row after the first; the second runs the handler again | N/A |
| Cleanup | rows at 25 h and 1 h old; the job runs | the 25 h row is deleted, the 1 h row kept; one INFO line with the count | N/A |
| Migrate profile | `migrate` run | no scheduler bean; exit code 0 as before | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/logging/{CorrelationIdFilter,RequestLoggingFilter}.java` -- `ORDER` constants (`HIGHEST_PRECEDENCE + 10`, then `+ 11`); the new filter is ordered `RequestLoggingFilter.ORDER + 1`. `LogSafe.clean` is for logging keys.
- `backend/src/main/java/com/company/seed/common/error/web/ApiErrorResponseWriter.java` -- `write(request, response, status, code, message)` (line ~67) writes the error envelope from a filter. `SafeMessages` is package-private in `common.error.web`; pass a safe message string.
- `backend/src/main/java/com/company/seed/common/error/ErrorCode.java` -- has `IDEMPOTENCY_KEY_REUSED`, `MALFORMED_REQUEST`; add `IDEMPOTENCY_REQUEST_IN_PROGRESS`.
- `backend/src/main/java/com/company/seed/common/actor/SystemActor.java` -- `run("system:…", Runnable)` for the cleanup.
- `backend/src/main/java/com/company/seed/config/{ClockConfig,MigrateRunner}.java` -- the UTC `Clock`; `migrate` closes the context after startup.
- `backend/src/main/resources/db/migration/common/V202611160900__common_grant_app_role.sql` -- the default privileges already cover new owner tables and sequences.
- `backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java` -- the Repository-Pattern rules and `SPRING_DATA_DECLARES_NO_DELETE` must pass unchanged.
- `backend/src/test/java/com/company/fixtures/db/TestDatabase.java`, `fixtures/persistence/*`, `fixtures/web/ProbeController.java` -- the shared test DB, mutable clock and probe-controller patterns to reuse.
- `_snap-output/specs/spec-monorepo-seed/api-standards.md` §1–§2 -- add the new code.

## Things this relies on

- `ContentCachingResponseWrapper`, `HttpServletRequestWrapper`, `OncePerRequestFilter` -- Spring Framework 7 / Jakarta Servlet 6.1
- PostgreSQL `INSERT … ON CONFLICT DO NOTHING`, `bytea` -- PostgreSQL 16
- `@EnableScheduling`, `@Scheduled(cron = …)` -- Spring Framework 7
- `@ConfigurationProperties` records with `Duration` binding -- Spring Boot 4.1.2
- `java.security.MessageDigest` SHA-256, `java.util.HexFormat` -- JDK 21
- `seed.idempotency.{retention,abandon-after,cleanup-cron}` -- new settings introduced here (defaults 24 h, 5 min, `0 0 * * * *`)
- the `migrate` profile and `MigrateRunner` -- story 6 (`config/`)
- Flyway 12 (migration), ArchUnit 1.4.2 (`SeedArchitectureRules`), Testcontainers 2 + Docker (`TestDatabase`) -- already in the build
- `EntityManager#createNativeQuery#executeUpdate` -- Jakarta Persistence 3.2 / Hibernate 7

## Tasks & Acceptance

**Execution:**
- [x] `src/main/resources/db/migration/common/V202611161100__common_create_idempotency_record.sql` -- the table, constraints and index from Always.
- [x] `common/idempotency/IdempotencyRecordRepository.java` (+ small value types) -- claim, take over, complete, release, delete-older-than.
- [x] `common/idempotency/infrastructure/persistence/{JpaIdempotencyRecordRepository,IdempotencyRecordJpaEntity,IdempotencyRecordSpringDataRepository}.java` -- the implementation, with native SQL for claim, take-over and retention.
- [x] `common/idempotency/IdempotencyFilter.java`, `IdempotencyProperties.java` (`seed.idempotency.{retention,abandon-after,cleanup-cron}`) and `IdempotencyCleanup.java`.
- [x] `config/SchedulingConfig.java` -- `@EnableScheduling`, `@Profile("!migrate")`.
- [x] `common/error/ErrorCode.java` -- the new code; `api-standards.md` §1–§2 updated to match.
- [x] Tests:
  - `IdempotencyIT` (`RANDOM_PORT`, test-only probe controller with an invocation counter and a latch for the in-progress row; mutable clock);
  - `IdempotencyPersistenceIT` (repository against PostgreSQL);
  - `IdempotencyCleanupIT`;
  - the `migrate` row in `MigrationIT`.
  - Every matrix row is covered.
- [x] `_snap-output/implementation-artifacts/deferred-work.md` -- story 7: scope keys to the authenticated caller, so one user can never replay another's stored response. Story 9: move the cleanup into `ScheduledJob`.
- [x] `README.md` -- one line on `Idempotency-Key` and the `seed.idempotency.*` settings.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, `api.yaml` is unchanged, and the architecture rules pass without changes.

## Implementation Notes

- Planning: one critic pass (10 findings), all resolved by spec edits the user approved. The user's "apply your recommendation" included proceeding without a second pass (2026-11-16).

- Implementation (subagent): all tasks done. Choices beyond the spec:
  - the filter takes the repository `@Lazy`, because filters are built before the test DB properties register;
  - the repository wraps the `EntityManagerFactory` with `SharedEntityManagerCreator`;
  - the entity's JPA `@Id` is `idempotency_key`, and the identity `id` column is unmapped;
  - `sendError`/`sendRedirect`/1xx/3xx outcomes release the key instead of being stored;
  - `Callable` handlers complete on the ASYNC dispatch;
  - a repeated header is 400;
  - a database failure while storing or releasing logs WARN and still answers;
  - startup validates the durations (`retention` > `abandon-after` > 0);
  - `GlobalExceptionHandlerTest` excludes the new filter;
  - `ReadinessIT$NeverMigrated` and the Flyway-off `MigrationIT` run set `ddl-auto=none`, because `validate` now fails startup on a missing mapped table.
- Known limits it reported: keyed bodies are read fully into memory (no size cap until story 7); a keyed form-urlencoded POST loses body parameters; the cleanup's `system:job:idempotency-cleanup` actor is not asserted.
- Main agent, in Verify: the subagent had added `resolution:` lines inside two existing deferred-work.md entries (6c, R19). They were removed, since existing entries are not modified; the resolution is recorded here (R19 is settled by native-SQL deletes, so no exemption is needed).
- Verify: `./gradlew clean build` passes with 325 tests in 35 suites and 0 failures (35 s); `api.yaml` is unchanged; snap-guard reports 0 findings across 30 files.

- Review pass 1 patches P1–P12 were applied by the implementation subagent (re-engaged): expired rows are treated as absent and a take-over resets `created_at`; keyed form/multipart writes pass through; `seed.idempotency.max-body-size` (1MB) gives 413 for requests and skips storing larger responses; claim-phase failures give 503; superseded claims log a WARN; bad charsets raise `UnsupportedEncodingException`; schema CHECKs were added; `cleanupCron` was removed; a shared `CorrelationIdFilter.TOKEN`; new tests (method variance, `sendError`, stub-repository filter test, accepted keys, cleanup actor, properties); docs. Deferrals D1 (story 7 filter order) and D2 (storage-story multipart) were appended.
- Open for the human: R29, fail-fast startup on a missing mapped table versus CAP-7's "readiness DOWN".
- Final verification (main agent): `./gradlew clean build` passes with 359 tests in 37 suites and 0 failures (37 s). The build also passes with `SPRING_PROFILES_ACTIVE=local` and `SEED_API_DOCS_ENABLED=true` exported. `api.yaml` is unchanged. snap-guard: 0 findings across 34 files. Doubt scan: clean. Context lake: none.

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 53 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | security + blind | Filter runs before where story 7's security chain goes: replays would skip authentication and security headers; 401/403/429 from security or rate limiting would be stored for 24 h | high (once story 7 lands) | `ORDER = HIGHEST_PRECEDENCE + 12` < Spring Security's -100. No security exists today, so nothing is exposed yet; the existing story 7 entry covers caller scoping but not ordering. | defer (D1) |
| R2 | security | Keys are global: cross-caller replay, existence oracle, squatting | medium | True; already deferred to story 7 at planning (the Never list). | rejected (already deferred) |
| R3 | blind + edge | Expired rows are still honoured until the next cleanup (forever with `cleanup-cron=-`); take-over keeps the old `created_at`, so a late-completed response is purged early | medium | `claim`/`findByKey` never compare `created_at` with the retention; the take-over SQL does not touch `created_at`. | patch (P1) |
| R4 | blind + security + verif-gap + edge | Keyed form-urlencoded and multipart writes lose their parameters and parts | medium | `CachedBodyRequest` re-serves only the stream; the container's `getParameter*`/`getParts` read the already-drained stream. | patch (P2) + defer (D2) |
| R5 | security + blind + edge | Request body read with no size limit; response bodies buffered and stored with no limit | medium | `readAllBytes()` before any limit; `ContentCachingResponseWrapper` plus `bytea` with no cap; the global body limit is story 7. | patch (P3) |
| R6 | edge | A repository failure while claiming escapes as a raw exception (500 via `/error`) | medium | `claim`/`findByKey`/`takeOver` are not guarded; a database outage should be 503. | patch (P4) |
| R7 | blind | A superseded claim (double execution) leaves no trace | medium | `finish()` ignores the `false` from `complete`/`release`. | patch (P5) |
| R8 | security + edge | Unknown request charset gives an unchecked exception (500) | low | `Charset.forName(encoding)` in `getReader()`; the Servlet contract is `UnsupportedEncodingException`. A direct fix. | patch (P6) |
| R9 | blind + edge | Schema does not enforce `COMPLETED ⇒ response_status NOT NULL`, key length or hash length | low | `toRecord` unboxes `response_status`; the migration has not shipped, so adding CHECKs is direct. | patch (P7) |
| R10 | slop + blind | `IdempotencyProperties.cleanupCron` is bound but never read; its Javadoc restates the cron | low | `@Scheduled` reads the placeholder directly. | patch (P8) |
| R11 | verif-gap + edge | `IdempotencyProperties` validation untested; extreme durations | low | Pre-verified gap for the validation (patch); the extreme-`Duration` overflow is unlikely, and a cap adds rules. | patch (P8); overflow rejected |
| R12 | slop | Header-token regex duplicated from `CorrelationIdFilter` | low | Byte-identical pattern; api-standards gives both the same format. | patch (P9) |
| R13 | verif-gap | Same key with a different method untested | medium | Pre-verified: no test varies the method under one key. | patch (P10) |
| R14 | verif-gap + edge | `sendError`/`sendRedirect` release path untested | medium | Pre-verified: no idempotency test calls `sendError`; story 7's security handlers will. | patch (P10) |
| R15 | verif-gap | Database failure in `complete`/`release` untested | medium | Pre-verified: no stub or failing repository in any test. | patch (P10) |
| R16 | verif-gap | Accepted keys with `.`, `_`, `:` and exactly 64 characters untested | medium | Pre-verified. | patch (P10) |
| R17 | verif-gap + edge | Claim retry loop's re-check and lost-race branches never run; exhausting it answers "in progress" with no row | low | Narrow window; the stub-repository filter test (P10) reaches the `continue` branch cheaply. A different answer on exhaustion adds a branch for an unlikely case. | patch (P10, test only); exhaustion rejected |
| R18 | blind | Cleanup actor untested; `IdempotencyCleanupIT` wipes the shared table | low | The story itself noted the actor gap; a whole-table delete breaks parallel ITs. | patch (P10, P12) |
| R19 | edge | Async request ending without a final ASYNC dispatch, or no capturing wrapper found, leaves the claim `IN_PROGRESS` | low | Recovery by take-over after `abandon-after` already exists; an `AsyncListener` adds complexity for a rare path. | rejected |
| R20 | edge + blind | Streaming responses (emitters, SSE, `StreamingResponseBody`) on keyed writes are fully buffered | low | Writes rarely stream; the P3 size cap bounds the memory. | rejected |
| R21 | edge | `setReadListener` throws on the cached request | low | Non-blocking reads are not used by Spring MVC handlers. | rejected |
| R22 | blind | Fingerprint ignores `Content-Type`/`Content-Encoding` | low | The fingerprint is defined in the frozen spec; changing it changes intent. | rejected |
| R23 | blind | Cleanup is one unbounded `DELETE` | low | Hourly runs keep each batch at about one hour of keys; story 9 moves it into `ScheduledJob`. | rejected |
| R24 | security | Response bodies stored in plaintext for 24 h; replay lacks later-filter headers | low | Inherent to replay (api-standards §1); the header part is solved by D1's re-ordering. | rejected (header part in D1) |
| R25 | security | Cleanup has no cross-instance lock | low | Already deferred to story 9 (option a). | rejected (already deferred) |
| R26 | slop | Repository builds a shared `EntityManager` by hand | low | Works and is documented in Implementation Notes; constructor injection failed in this wiring. | rejected |
| R27 | blind | api-standards §1 and README do not describe releasing 1xx/3xx/container-answered responses, dropped headers, or the in-progress 409; README line unwrapped | low | Direct text corrections. | patch (P11) |
| R28 | security + blind | Known limits (form bodies, body size) only in the story notes | low | Resolved by P2/P3 plus D2. | patch (P2, P3) |
| R29 | implementer note | The first mapped table makes `ddl-auto=validate` fail startup when its migration is missing, while CAP-7 says "readiness DOWN if the schema is behind" | medium | Exposed by this story; rooted in story 6's approved `validate` setting, so outside this spec's frozen intent. | defer to the human (decision asked in the completion summary) |

## Design Notes

**Why a committed `IN_PROGRESS` claim before the handler:** the unique key makes the claim atomic across threads and instances. Committing it first is what lets a concurrent duplicate see it and get 409, rather than both running the handler.

**Why a 5xx releases the key:** a server failure says nothing about whether the client's request was right, so the client's retry must be allowed to run. A 4xx is the answer to that exact request and is replayed.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `api.yaml` unchanged.

## Tech Spec Critic Findings

- [SOLVED] [medium] check-1 | spec line 41 | The intent gives a duplicate arriving while the first request is in progress 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`, but under the 60 s `abandon-after` takeover a duplicate of a handler running longer than 60 s runs the handler a second time — the double write the feature exists to prevent.
- [SOLVED] [medium] check-1 | spec line 55 | The intent asks to exempt the cleanup from the no-`delete`/`remove` ArchUnit rule, but the spec keeps the rule unchanged and works around it with native SQL without saying it departs from the request, and the deferred-work task does not close the R19 item.
- [SOLVED] [medium] check-5 | spec line 45 | "deletes the row" / "the row becomes `COMPLETED`" can mean the row for that key whoever holds it or only this request's claim; after a takeover, the first, slow request finishing late would overwrite or delete the second request's stored outcome under the first reading.
- [SOLVED] [medium] check-6 | spec line 99 | The new settings `seed.idempotency.retention`, `seed.idempotency.abandon-after`, `seed.idempotency.cleanup-cron` and the `migrate` profile are missing from `## Things this relies on`.
- [SOLVED] [low] check-6 | spec line 99 | Flyway, ArchUnit and Testcontainers/Docker are used but not listed in `## Things this relies on`.
- [SOLVED] [low] check-2 | spec line 46 | The message texts of the 400 `MALFORMED_REQUEST` and 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` envelopes are not stated, so the client may not learn the header was the problem or that it should retry later (no `Retry-After`).
- [SOLVED] [low] check-3 | spec line 76 | No matrix row states the replay's correlation behaviour (current ID in the header, original `correlationId` in the body).
- [SOLVED] [low] check-3 | spec line 77 | No matrix row covers a key with an `IN_PROGRESS` row and a different fingerprint.
- [SOLVED] [low] check-5 | spec line 36 | "path" can mean the raw encoded request URI or the decoded servlet path (with or without context path), giving different fingerprints for the same logical request.
- [SOLVED] [low] check-1 | spec line 60 | The new `Idempotent-Replayed` response header is not added to the api-standards.md §8 header table.
