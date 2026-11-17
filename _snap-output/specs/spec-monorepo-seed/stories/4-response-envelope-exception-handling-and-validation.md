---
title: 'Story 4 — Response envelope, exception handling and validation'
type: 'feature'
created: '2026-11-14'
status: 'done'
baseline_commit: 'c44319e46cfa89e83c74ccd833c0fb655162e708'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The backend has no response envelope, error handling or validation infrastructure, so every later story would invent its own response and error shapes (CAP-3, CAP-4, CAP-5 backend).

**Approach:** Add the envelope records and factory in `common.api`, the `SeedException` hierarchy in `common.error`, one `GlobalExceptionHandler` plus a JSON `/error` controller in `common.error.web`, and validation groups and messages in `common.validation`. The envelope shape is unchanged from api-standards.md §3 (decided 2026-11-14: keep current `ApiResponse`; not the seed-document variant or RFC 9457).

## Boundaries & Constraints

**Always:**
- Shapes, status codes and error codes exactly as api-standards.md §2–§3 and errors-and-logging.md §1–§3. `errors` is always present (empty list when none); `data` is always written (null allowed); `pagination` only on paged responses.
- `timestamp` from an injected `Clock`; `correlationId` from MDC key `correlationId` (null until story 5 adds the filter).
- Every handled exception is logged once, by the handler, at the level in errors-and-logging.md §2; 5xx at ERROR with stack trace and the correlation ID.
- Response `message` is a fixed safe text, never the exception's own message, SQL, class name or stack trace.
- `common.error` (excluding `common.error.web`) has no Spring imports — enforced by a new ArchUnit rule.
- New ArchUnit rule: every public method of a `@RestController` in `com.company..` returns `ResponseEntity<ApiResponse<…>>` where `ApiResponse` is the `common.api` type. Each new rule gets a violating fixture and a compliant one.

**Never:**
- No Spring Security, Spring Data/JPA or rate-limit mappings (deferred to stories 7 and 6 in deferred-work.md); no new dependencies.
- No correlation filter, structured logging, OpenAPI or `PageQuery`/sort whitelisting (stories 5 and 8).
- No `ProblemDetail` / `ResponseEntityExceptionHandler` output; one error format only.
- Test-only controllers never live under `com.company.seed`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Success | `ApiResponses.ok/created/accepted/deleted` | 200/201+`Location`/202+`Location`/200; body has `success,message,data,timestamp,correlationId`; `deleted()` writes `"data":null`; no `pagination` key | N/A |
| Paged | `ApiResponses.page(PageResult)` | 200, `data` = items, `pagination` = `{page,size,totalElements,totalPages,hasNext}` | N/A |
| Body validation | nested object, `List<@Valid Item>` with `items[2].name` blank, `OnCreate` vs `OnUpdate` rule | 400 `VALIDATION_FAILED`, one `errors[]` entry per field path, `code` = constraint simple name, message from `ValidationMessages.properties` | DEBUG |
| Sensitive / long value | `@Sensitive` field invalid; 150-char invalid value | `rejectedValue` omitted; long value truncated to 100 chars | DEBUG |
| Param validation | `@Validated` controller, bad `@Min` query param / path variable | 400 `VALIDATION_FAILED`, `field` = parameter name | DEBUG |
| Malformed | bad JSON, type mismatch, missing required param | 400 `MALFORMED_REQUEST` | DEBUG |
| Not found | unknown route; thrown `ResourceNotFoundException` subclass | 404 `NOT_FOUND`; 404 with its own code | DEBUG |
| Method / media type | wrong method; wrong `Content-Type` | 405 `METHOD_NOT_ALLOWED`; 415 `UNSUPPORTED_MEDIA_TYPE` | DEBUG |
| Seed exceptions | `ConflictException`, `BusinessRuleException`, `ForbiddenOperationException`, `ExternalServiceException` | 409 own code / 422 own code / 403 `FORBIDDEN` / its 502·503·504 and own code | INFO / INFO / WARN / WARN |
| Too large | `MaxUploadSizeExceededException` | 413 `PAYLOAD_TOO_LARGE` | INFO |
| Unexpected | `RuntimeException("select * from secret_table")` | 500 `INTERNAL_ERROR`, message "Unexpected error. Quote the correlation ID."; body has no SQL, class name or stack | exactly one ERROR log line with stack and correlation ID |
| `/error` | request dispatched to `/error` (e.g. servlet-level failure) | error envelope with the original status, `path` = original URI | as above by status |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/package-info.java` -- OPEN module, so new sub-packages are visible everywhere. Read-only.
- `backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java` -- `public static final ArchRule` constants + `ALL` list; `WEB_API_LAYER` already exempts `common.api`. Add the two new rules here and to `ALL`.
- `backend/src/test/java/com/company/fixtures/ArchitectureRulesDetectViolationsTest.java` -- `violations()` pairs each rule with a fixture package under `com.company.fixtures.archunit.`; `everyRuleHasAFixture` and `ruleAcceptsThePrescribedLayout` (checks `…archunit.compliant`) fail until both new rules have fixtures.
- `backend/src/test/java/com/company/fixtures/archunit/compliant/` -- add a compliant `@RestController` and a compliant `common.error` class; the existing `compliant/common/api/PageResult.java` shows the pattern.
- `backend/src/main/resources/application.yml` -- add `server.error.include-*: never`.
- `backend/build.gradle.kts` -- read-only; `*IT` classes run in `integrationTest`; `spring-boot-starter-validation`, `-webmvc`, `-webmvc-test` already present.

## Things this relies on

- `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` -- spring-boot-webmvc-test 4.1.2
- `org.springframework.boot.webmvc.error.ErrorController` -- spring-boot-webmvc 4.1.2
- `HandlerMethodValidationException`, `MaxUploadSizeExceededException` -- spring-web 7.0.10
- `NoResourceFoundException` -- spring-webmvc 7.0.10
- Jakarta Bean Validation / `ValidationMessages.properties` -- hibernate-validator 9.1.5.Final
- `com.fasterxml.jackson.annotation.JsonInclude` -- jackson-annotations 2.21 (used by tools.jackson databind 3.1.7)

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/company/seed/config/ClockConfig.java` -- `Clock.systemUTC()` bean -- envelope timestamps; story 6 reuses it.
- [x] `backend/src/main/java/com/company/seed/common/api/` -- `ApiResponse`, `PageMeta`, `ApiErrorResponse`, `FieldError`, `PageResult<T>` records and the `ApiResponses` factory bean -- the only response wrappers.
- [x] `backend/src/main/java/com/company/seed/common/error/` -- `SeedException` and the five subclasses, `ErrorCode` constants for the generic codes -- framework-free.
- [x] `backend/src/main/java/com/company/seed/common/error/web/` -- `GlobalExceptionHandler`, `ApiErrorResponseWriter` (builds the body; writes it to a raw `HttpServletResponse` for filters), `JsonErrorController` (`@Controller` implementing `ErrorController`) -- one error path.
- [x] `backend/src/main/java/com/company/seed/common/validation/` + `backend/src/main/resources/ValidationMessages.properties` -- `OnCreate`, `OnUpdate`, `@Sensitive` -- validation infrastructure.
- [x] Architecture rules + fixtures (see Code Map) -- envelope return type; framework-free `common.error`.
- [x] Tests: `common/api/ApiResponsesTest` (unit, fixed `Clock`, MDC set); `common/error/web/GlobalExceptionHandlerTest` (`@WebMvcTest` + probe controller and DTOs in `com.company.fixtures.web`, one test per matrix row, log capture for the 500 row); `common/error/web/EnvelopeContractIT` (`@SpringBootTest(RANDOM_PORT)` + `RestClient`, imports the probe controller, calls every probe route and asserts the key sets of the success and error shapes, including `/error`).

**Acceptance Criteria:**
- Given the change, when `./gradlew clean build` runs on JDK 21, then it passes with the previous 31 tests plus the new ones, and `integrationTest` runs `EnvelopeContractIT`.
- Given a controller method returning anything other than `ResponseEntity<ApiResponse<…>>`, when `ArchitectureTest` runs, then it fails.

## Implementation Notes

- `message`: generic/framework errors get a fixed text per code (`SafeMessages`); a `SeedException` subclass gets its own `safeMessage` (it exists for that). `JsonErrorController` uses the same fixed texts.
- Nulls: `correlationId`, `path` and `data` are always written (null allowed); only `pagination` and `FieldError.rejectedValue` use `NON_NULL`. `rejectedValue` is therefore left out for `@Sensitive` fields, for non-scalar values (objects, collections — they could carry nested sensitive fields), and when the value is null or no `ConstraintViolation` is available.
- `OnCreate`/`OnUpdate` extend `jakarta.validation.groups.Default`, so `@Validated(OnCreate.class)` also checks ungrouped constraints.
- `errors[]` is sorted by `field`, then `code` (Bean Validation reports violations in set order). Class-level (global) errors have `field: null`.
- `ExternalServiceException` logs at WARN with `dependency=` (matrix and catalogue), not ERROR; any other `SeedException` with a 5xx status logs at ERROR with stack.
- Non-ERROR log lines name only the exception type for framework exceptions, because their texts echo request input (`MethodArgumentNotValidException` includes rejected values, so a `@Sensitive` value would otherwise reach DEBUG logs).
- `/error`: status → code is 400 `MALFORMED_REQUEST`, 401 `UNAUTHENTICATED`, 403 `FORBIDDEN`, 404 `NOT_FOUND`, 405, 413, 415, 429 `RATE_LIMITED`, 503 `SERVICE_UNAVAILABLE`; any other 4xx → `MALFORMED_REQUEST`, anything else → 500 `INTERNAL_ERROR`. A direct request to `/error` (no error attributes) answers 404 `NOT_FOUND`.
- 405 responses carry an `Allow` header. Error responses set `Content-Type: application/json` explicitly, so they are written whatever the `Accept` header says.
- Known: an exception thrown in a servlet filter is also logged by Tomcat (`StandardWrapperValve`) before `JsonErrorController` logs it, so that case produces two ERROR lines.
- Not mapped (fall through to 500 `INTERNAL_ERROR`): other Spring MVC exceptions outside the catalogue, e.g. `HttpMediaTypeNotAcceptableException` (406), `MissingRequestHeaderException`, `MissingServletRequestPartException`.
- Review pass 1 fixes (applied by the main agent; the implementation subagent could not be resumed):
  - P1: Spring MVC `ErrorResponse` exceptions outside the catalogue (406, missing header, `ResponseStatusException`) answer with their own status via the shared `StatusMapping` (also used by `JsonErrorController`), not 500.
  - P2: `AsyncRequestNotUsableException` (client gone) is logged once at DEBUG and nothing is written.
  - P3: a `SeedException` thrown in a filter (also wrapped in `ServletException`) keeps its status, code and safe message at `/error`.
  - P4: non-ERROR log lines append `details=` and, for `ExternalServiceException`, `cause=`.
  - P5: `isSensitive` uses `ReflectionUtils.findField`.
  - P6/P7: the envelope rule checks public and request-mapped methods (any visibility), skips bridge/synthetic methods; the violation fixture adds a look-alike `ApiResponse` and a package-private handler; a test asserts each is named.
  - P8: `/error` maps 502 → `EXTERNAL_SERVICE_ERROR`, 504 → `EXTERNAL_SERVICE_TIMEOUT`; every mapped status is tested.
  - P9: method-validation `ParameterErrors` for container elements are prefixed with `[index]`; body and list probes added.
  - P10: nested `@Sensitive` and container-level constraint values are tested as not echoed.
  - P11: binding errors without a constraint violation (`typeMismatch`) use the message "Invalid value".
  - P12/P13: truncation never splits a surrogate pair; Set elements render as `tags[]`.
  - P14/P15: null violation sets are tolerated; return-value validation failures go to 500.
  - P16/P17/P18: `SeedException` rejects statuses outside 400–599 and accepts null detail values; `PageResult.totalPages()` cannot overflow silently.
  - P19: `EnvelopeContractIT` calls every probe route, including PUT, 502, 504 and the new routes.

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-14). Layers: security (S), slop (SL), blind (B), verification-gap (VG), edge-case (E).

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| S1 | `ConstraintViolationException` from internal services / JPA entities mapped to 400 with internal paths and values | maybe-false | No service-level `@Validated` or JPA exists in main today; settles once story 6 adds Hibernate entity validation (would be medium) | defer |
| S2 | `@Sensitive` cannot mark query/path parameters | low | True (`@Target(FIELD)`), but secrets in query/path are not a pattern here and the fix adds branches in two paths | reject |
| S3 | `@Sensitive` on a parent object does not cover nested fields | low | True, but the documented use is per field (mark `password` itself); ancestor walk adds complexity | reject |
| S4 | Unmapped client-side framework exceptions (406, missing header/part, `ResponseStatusException`) become 500 + ERROR stack | medium | Verified: `handleUnexpected` catches every `ErrorResponse` subtype; `Accept: application/xml` on any endpoint reaches it | patch P1 |
| S5 | Catch-all at `HIGHEST_PRECEDENCE` blocks other advice and future security exceptions | false | errors-and-logging.md §2 makes this the single advice; security mapping into this handler is already deferred to story 7; `ResponseStatusException` is covered by P1 | reject |
| S6 | 403 vs 404 lets callers enumerate resources | false | Status split is the catalogue's (§1); the feature chooses which exception to throw | reject |
| S7 | `safeMessage` is free text, nothing enforces safety | low | Convention by design (§1 `safeMessage`); cannot be enforced without heavy machinery | reject |
| S8 | Map keys reflected untruncated in `field`; `errors[]` uncapped | low | No Map-typed DTO exists; Angular escapes output; body size limit is story 7 | reject |
| S9 | Raw URI logged and reflected as `path` | low | `path` is the specified envelope field; Tomcat rejects CR/LF | reject |
| S10 | No request body size limit; 413 only for multipart | medium | True and not caused by this change (no limit existed); request limits are story 7 | defer |
| S11 | `write()` keeps headers set earlier | low | Only a probe filter calls it; realistic callers write before the chain; fix needs header policy | reject |
| SL1 | Hand-written superclass field walk duplicates `ReflectionUtils.findField` | low | Verified `FieldErrorMapper.isSensitive`; direct replacement | patch P5 |
| SL2 | `fieldPath` duplicates `SpringValidatorAdapter.determineField` | low | Spring method is protected and lacks the PARAMETER case; reuse needs a subclass | reject |
| B1 | Same as S5 | false | See S5 | reject |
| B2 | Same as S4 | medium | See S4 | patch P1 |
| B3 | Same as S1 | maybe-false | See S1 | defer |
| B4 | `JsonErrorController` ignores the exception type: a `SeedException` thrown in a filter becomes 500 `INTERNAL_ERROR` | medium | Verified: `Mapping.of(status)` only; Tomcat dispatches filter exceptions with 500 | patch P3 |
| B5 | `details` never logged; `ExternalServiceException` cause dropped; `retryable` unused | medium | Verified `log()` prints `ex.toString()` only below ERROR; cause lost for every 502–504. `retryable` is for async retry (story 9) — not a defect | patch P4 |
| B6 | Same as S2 | low | See S2 | reject |
| B7 | Envelope rule checks public methods, misses non-public/inherited handlers, flags `@InitBinder` | medium | Verified: a package-private `@GetMapping` returning `String` passes; AC says any handler fails. `@Controller`+`@ResponseBody` and `@InitBinder` parts are low and rejected | patch P7 |
| B8 | Envelope fixture never proves the `common.api` package check | medium | Same as VG1 | patch P6 |
| B9 | Nothing stops `new ApiResponse<>(…)` by hand | low | True; needs a new rule and fixtures; not required by the story | reject |
| B10 | `write()` takes free-text message, no headers | low | Story 7 shapes its callers; no defect today | reject |
| B11 | `COMMON_ERROR_IS_FRAMEWORK_FREE` only bans Spring | false | Spec and §1 say "no Spring imports" | reject |
| B12 | Truncation can split a surrogate pair | low | Verified `subSequence(0, 100)`; direct fix | patch P12 |
| B13 | Spec contradictions (5xx WARN, null correlation ID, double logging, relies-on list) | low | Fixes are spec edits, which triage may not make; double ERROR line only when a filter throws, none in main | reject |
| B14 | deferred-work.md missing STOMP/async and other deferrals | low | STOMP `@MessageExceptionHandler` and async-listener handling are uncovered and unlisted | defer |
| B15 | Untested claims (Accept xml, global errors, non-scalar, nested sensitive, committed write, guards, real multipart, 502/504 IT) | medium | Nested sensitive / non-scalar folded into P10, 502/504 into P19, Accept xml into P1; the rest are low and rejected | patch P1/P10/P19 |
| VG1 | Envelope rule's package check not pinned by any test | medium | Pre-verified gap | patch P6 |
| VG2 | `/error` status→code mapping tested only for 403/500 | medium | Pre-verified gap | patch P8 |
| VG3 | `ParameterErrors` branch of `fromMethodValidation` never exercised | medium | Pre-verified gap | patch P9 |
| VG4 | Redaction tested only for a top-level `@Sensitive` string | medium | Pre-verified gap | patch P10 |
| E1 | `typeMismatch` binding errors put Spring's message (class names, raw input) into `FieldError.message` | medium | Verified: `fromErrors` uses `getDefaultMessage()` when no violation; `@ModelAttribute` binding raises `MethodArgumentNotValidException` | patch P11 |
| E2 | `@Valid @RequestBody List<Item>` via method validation drops the element index | medium | Verified: `ParameterErrors` branch ignores `getContainerIndex()` | patch P9 |
| E3 | Same as S2 | low | See S2 | reject |
| E4 | Set element renders as `tags[null]` | low | Verified `node.getKey()` appended when null; direct fix | patch P13 |
| E5 | Same as S3 | low | See S3 | reject |
| E6 | Getter-only constraint with different field name skips `@Sensitive` | low | Records and field constraints are the project style; rare | reject |
| E7 | Same as B12 | low | See B12 | patch P12 |
| E8 | `ConstraintViolationException` with null violations NPEs in the handler | low | Verified jakarta keeps null; direct null guard | patch P14 |
| E9 | Return-value method validation reported as client 400 | low | Verified: `handleMethodValidation` ignores `isForReturnValue()`; direct fix | patch P15 |
| E10 | Same as S4 | medium | See S4 | patch P1 |
| E11 | Client abort (`AsyncRequestNotUsableException`) logged at ERROR and re-written | medium | Verified: the catch-all takes it before Spring's default resolver | patch P2 |
| E12 | Direct `SeedException` subclass may use status 0/200 | low | Constructor is protected and unchecked; one-line guard | patch P16 |
| E13 | `details` with a null value NPEs in `Map.copyOf` | low | Verified; direct fix | patch P17 |
| E14 | `totalPages` overflows for huge totals | low | Verified arithmetic; direct fix | patch P18 |
| E15 | `write()` after `getWriter()` / stale Content-Length | low | No such caller; fix needs branches | reject |
| E16 | `/error` 502/504 map to `INTERNAL_ERROR` | low | Verified `Mapping.of` default; two cases | patch P8 |
| E17 | `/error` 405 has no `Allow` header | low | Container-level 405 is rare; forwarding headers adds logic | reject |
| E18 | Same as B7 | medium | See B7 | patch P7 |
| E19 | Bridge methods give false envelope violations | low | Verified rule has no `BRIDGE` exclusion; one predicate | patch P7 |
| E20 | 500 message asks to quote a null correlation ID | low | Spec-defined text; story 5 supplies the ID; fix would edit the spec | reject |
| E21 | `EnvelopeContractIT` does not call PUT, 502, 504 probe routes, contrary to its task | low | Verified CsvSource rows; direct additions | patch P19 |
| E22 | Same as B7 (acceptance criterion claim) | medium | See B7 | patch P7 |

## Design Notes

- `ApiResponses` is a Spring bean (injected `Clock`), not static, so tests pin time without static state; controllers call `apiResponses.created(message, data, location)`. Each factory method takes the `message`.
- Field paths: `MethodArgumentNotValidException` field names are already dotted with indices; `ConstraintViolationException` paths drop the leading method segment.
- `@Sensitive` is checked on the DTO field behind the violated path.

## Verification

**Commands** (on this machine Gradle needs JDK 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` first; see README):
- `cd backend && ./gradlew clean build` -- expected: BUILD SUCCESSFUL; test reports show the new test classes ran with 0 failures.
- `cd backend && ./gradlew spotlessCheck` -- expected: passes.

## Tech Spec Critic Findings

- [high] check-1 | spec line 50 | The intent says every 5xx is logged once at ERROR with its correlation ID, and spec line 27 says the same, but this row logs `ExternalServiceException` (502/503/504) at WARN, so the spec contradicts both the intent and itself.
- [high] check-6 | spec line 66 | `## Things this relies on` leaves out packages and APIs the Tasks use: ArchUnit (archunit-junit5) for the two new rules, SLF4J `MDC` for `correlationId`, `RestClient` and `@SpringBootTest` (spring-web / spring-boot-test) for `EnvelopeContractIT`, and the log-capture mechanism for the 500 row.
- [medium] check-6 | spec line 66 | The list leaves out the `server.error.include-*` settings that the Code Map adds to `application.yml` and that the "no stack/class name" guarantee depends on.
- [medium] check-5 | spec line 28 | "a fixed safe text, never the exception's own message" can be read two ways for `SeedException` subclasses, which carry a `safeMessage`: show that per-exception `safeMessage`, or show one generic text per status. The matrix rows (lines 44–51, 53) never say which `message` the consumer receives.
- [medium] check-5 | spec line 26 | `correlationId` is null until story 5, but the spec does not say whether a null `correlationId` (and `path`) is written as `null` or left out. Line 25 forces this only for `data` and `errors`, so the key-set checks of intent Scenario 1 could pass or fail depending on the reading.
- [medium] check-2 | spec line 52 | The 500 message tells the API consumer to "Quote the correlation ID", but line 26 makes `correlationId` null in every response and log line until story 5. The consumer has no ID to quote, and the intent's "logged at ERROR with its correlation ID" cannot be met.
- [medium] check-3 | spec line 53 | The `/error` row gives no `errorCode` or `message` for the forwarded status (for example servlet-level 500 versus 400 versus 404), so what the consumer receives from `/error` is not observable or testable.
- [medium] check-1 | spec line 84 | Intent Scenario 1 says the contract test calls each route in "the route table", but the spec's `EnvelopeContractIT` calls only hand-picked probe routes. Routes added in later stories would not be checked against the envelope.
- [medium] check-1 | spec line 33 | errors-and-logging.md §2 rules (STOMP `@MessageExceptionHandler` mapping to `ERROR` frames, and async-listener failure handling) are part of the referenced §2. The spec neither covers them nor lists them as deferred, and the human decision deferred only the security, rate-limit and persistence rows.
- [medium] check-1 | spec line 34 | The intent cites api-standards.md §4 (unknown sort field → 400 `VALIDATION_FAILED`). The spec defers `PageQuery`/sort whitelisting to story 8, but the recorded human decisions approved no such deferral.
- [low] check-1 | spec line 51 | The catalogue maps "`MaxUploadSizeExceededException` / size filter" to 413, but the spec covers only multipart uploads. A JSON body over the limit has no 413 path and is not named as deferred.
- [low] check-1 | spec line 98 | The `ApiResponses` factory is an injected bean whose methods all take a `message` (`created(message, data, location)`). This departs from api-standards.md §3 (`ApiResponses.ok(data)`, `.created(data, location)`), which line 25 says the spec follows "exactly", and the departure is not named.
- [low] check-5 | spec line 45 | "`rejectedValue` omitted" for `@Sensitive` fields can be read as leaving the key out or writing it as null. If null values are left out, a non-sensitive field rejected with a null value looks the same to the consumer as a sensitive one.
- [low] check-1 | spec line 34 | The frontend-parity requirement of errors-and-logging.md §3 (`docs/validation-rules.md`) is deferred only in deferred-work.md. The spec does not mention that deferral.
