---
title: 'Story 5 — Correlation ID, structured logging, OpenAPI export and health'
type: 'feature'
created: '2026-11-15'
status: 'done'
baseline_commit: 'd6d70c7c7717ca41fa41e3dd9587db139369a3b1'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Requests carry no correlation ID (`correlationId` is always null), logs are plain text with nothing masked, there is no committed API contract, and the app reports no health or metrics (CAP-9, CAP-15, CAP-19).

**Approach:** One packet (decided 2026-11-15: keep all three parts). Add `common.logging` (correlation filter, request summary line, `LogSafe`, masking customizer), a UUIDv7 `IdGenerator` in `common.ids` brought forward from story 6, springdoc 3.2.0 with a Gradle drift check, and Actuator probes plus Prometheus on management port 8081. Drift check (decided: option a): the build generates into `build/`, `checkOpenApi` (part of `check`) compares with the committed `backend/openapi/api.yaml`, and `updateOpenApi` accepts a change.

## Boundaries & Constraints

**Always:**
- `CorrelationIdFilter` runs first among the app's own filters and before the future security chain. Boot's framework filters that sort earlier, such as the character-encoding filter, may run before it. It accepts `X-Correlation-Id` matching `[A-Za-z0-9._:-]{1,64}`; otherwise it uses a new UUIDv7, and the rejected value is never logged. It puts the ID in MDC key `correlationId` (`ApiResponses.CORRELATION_ID_MDC_KEY`), sets the response header before the chain runs, and clears MDC afterwards. On an ERROR or ASYNC re-dispatch it restores the same ID, so the `/error` envelope carries it.
- One INFO line per request: method, route template (or `UNMATCHED`), status, duration in ms. No bodies, headers or query strings. `/actuator/**` is excluded. The line carries the status the client received and the original request's route, never `/error`. Normally the original dispatch writes it. When an exception escapes the chain, the ERROR re-dispatch writes it with the final status. When async processing has started, the final ASYNC dispatch writes it. The line is never written twice.
- `logging.structured.format.console=ecs` with `service.name=seed` in every profile except `local`. MDC entries appear as JSON members. `local` keeps readable lines, and `logging.pattern.correlation` puts `[<correlationId>]` on each one.
- A `StructuredLoggingJsonMembersCustomizer`, set through `logging.structured.json.customizer`, replaces the value of any member whose last path segment is `password`, `secret`, `token`, `authorization`, `apiKey`, `privateKey` or `email` (case-insensitive) with `******`. Masking applies only to structured output. Readable `local` lines are not masked, an accepted gap because `local` never handles real secrets. Message text is never masked: the protection there is that the code never logs headers, bodies or raw values.
- `LogSafe.clean(value)`: null stays null; C0 controls, DEL, C1 controls (U+0080–U+009F), U+2028 and U+2029 become `_`; the result is cut to 200 characters.
- The exported `api.yaml` is deterministic: a fixed server URL `/`, sorted keys, and the schemas `ApiResponse`, `ApiErrorResponse`, `FieldError` and `PageMeta` registered even when no endpoint references them.
- `/v3/api-docs` and Swagger UI are enabled only when `SEED_API_DOCS_ENABLED=true`. The code default is off, which covers prod. `application-local.yml` and `application-dev.yml` default the variable to `true`, and it can still be overridden. Story 7 puts the `dev` docs behind authentication.
- Actuator exposes only `health` and `prometheus`, on `management.server.port=8081`. Liveness and readiness groups are on, with `show-details: when-authorized` and `roles: ADMIN`.

**Never:**
- Nothing that needs a later story (each gets a `deferred-work.md` entry):
  - story 6: readiness checks for the database and schema;
  - story 7: `user.id`, and ADMIN-only `/actuator/loggers`;
  - story 9: `operationId`, the async `TaskDecorator`, STOMP correlation, per-job correlation IDs, and PG LISTEN readiness;
  - frontend stories: the Angular interceptor that sends `X-Correlation-Id`, and the `frontend/src/app/api/` client-drift check;
  - the owning stories: the `hikaricp_*` metrics (story 6), `seed_job_duration_seconds`, `seed_ws_sessions_active` and `seed_operation_total` (story 9), and `seed.storage.*` (the storage story);
  - OTLP tracing stays off, so there is no `traceId` or `spanId` until a trace backend exists.
- No `springdoc-openapi-gradle-plugin` or `bootRun`-based export.
- No logging of raw caller values without `LogSafe`.
- No Jackson 2 in application code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| ID supplied | `X-Correlation-Id: abc` on `/probe/ok` | response header `abc`, envelope `correlationId` `abc`, request log line has `"correlationId":"abc"` | N/A |
| ID missing / invalid | no header; `bad id!`; 65 chars | new UUIDv7 (version nibble 7) in header and envelope | the rejected value appears nowhere in the captured output |
| Error paths | handler exception; filter exception dispatched to `/error` | error envelope `correlationId` equals the response header | N/A |
| Masking (member) | SLF4J structured key-value `addKeyValue("password","hunter2")` and MDC `token=t0k` | ECS line has `"password":"******"` and `"token":"******"`; captured output contains neither `hunter2` nor `t0k` | N/A |
| No leak (request) | request with `Authorization: Bearer <jwt>` and body `{"password":"hunter2"}`, including a 400 | captured output contains neither the JWT nor `hunter2` | N/A |
| Request summary | `GET` on a template route such as `/probe/items/{id}` | one INFO line containing `GET`, the template (not `7`), `200` and a duration | N/A |
| Summary: unmatched / actuator / filter exception | unknown route; `:8081/actuator/health`; `ProbeFilter` throws | `UNMATCHED` with 404; no summary line; exactly one line, with 500 and not `/error` | N/A |
| LogSafe | `"a\r\nb"`; `a` + U+2028 + `b`; 250 characters; null | `a__b`; `a_b`; 200 characters; null | N/A |
| Contract content | generated `api.yaml` | `servers` is exactly `[{url: /}]`; `components.schemas` has `ApiResponse`, `ApiErrorResponse`, `FieldError`, `PageMeta`; two runs give identical bytes | N/A |
| Drift | committed `api.yaml` differs from the generated one | `./gradlew check` fails with a message naming `updateOpenApi` | N/A |
| Docs flag | flag off (default) / on | `/v3/api-docs` and `/swagger-ui/index.html` return 404 / 200 | N/A |
| Probes | `GET :8081/actuator/health/liveness` and `/readiness`, anonymous | 200 `{"status":"UP"}` with no `components` or `details` | N/A |
| Metrics | `GET :8081/actuator/prometheus` after one request | contains `http_server_requests_seconds` and `jvm_` | N/A |
| Actuator surface | `GET :8081/actuator/env`; `GET :<app port>/actuator/health` | 404; 404 (the error envelope) | N/A |
| Local readable lines | `local` profile, request with `X-Correlation-Id: abc` | pattern line (not JSON) containing `[abc]` | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/api/ApiResponses.java` -- `CORRELATION_ID_MDC_KEY = "correlationId"`; the envelope already reads it from MDC. Read-only.
- `backend/src/main/java/com/company/seed/common/error/web/{GlobalExceptionHandler,JsonErrorController,ApiErrorResponseWriter}.java` -- already read MDC for `correlationId`. Read-only; their logs gain the ID automatically.
- `backend/src/main/java/com/company/seed/common/package-info.java` -- OPEN module, so new `common.logging` and `common.ids` are visible everywhere.
- `backend/src/main/java/com/company/seed/config/` -- `ClockConfig` (UTC `Clock` bean, reused by `IdGenerator`); add `OpenApiConfig` here.
- `backend/src/main/resources/application.yml` -- extend it; there is no profile file yet.
- `backend/build.gradle.kts` -- has `failOnVersionConflict()`, enforced BOM platforms, and the `integrationTest` task (`*IT`) wired into `check`. Every version lives in `gradle/libs.versions.toml`.
- `backend/src/test/java/com/company/fixtures/web/{ProbeController,ProbeFilter}.java` -- test-only probe routes; `ProbeFilter` (default order) throws, calls `sendError` and writes errors outside MVC.
- `backend/src/test/java/com/company/seed/common/error/web/EnvelopeContractIT.java` -- the pattern for `RANDOM_PORT` tests: `@Import` probes and a `RestClient` with a no-op status handler.
- `.env.example` -- the list of runtime variables (add `SEED_API_DOCS_ENABLED`).

## Things this relies on

- `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0` -- Maven Central (parent `spring-boot-starter-parent` 4.1.2)
- `spring-boot-starter-actuator`, `io.micrometer:micrometer-registry-prometheus` -- Boot 4.1.2 BOM (Micrometer 1.17.2)
- `logging.structured.format.console`, `logging.structured.ecs.service.name`, `logging.structured.json.customizer` -- spring-boot 4.1.2 configuration metadata
- `StructuredLoggingJsonMembersCustomizer`, `JsonWriter.Members#applyingValueProcessor`, `JsonWriter.ValueProcessor#whenHasPath(Predicate)` -- spring-boot 4.1.2 jar
- An empty `CONSOLE_LOG_STRUCTURED_FORMAT` falls back to the pattern encoder -- `DefaultLogbackConfiguration` (`StringUtils.hasLength`)
- `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` -- Spring Framework 7
- `OutputCaptureExtension` -- spring-boot-test
- `@LocalManagementPort` -- spring-boot-test (`org.springframework.boot.test.web.server`)
- UUIDv7 layout -- RFC 9562 §5.7
- `logging.pattern.correlation` -- spring-boot 4.1.2 configuration metadata; the default console pattern includes `${LOG_CORRELATION_PATTERN}`
- `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled`, `springdoc.writer-with-order-by-keys`, the `/v3/api-docs.yaml` endpoint, and `org.springdoc.core.customizers.OpenApiCustomizer` -- springdoc 3.2.0
- `io.swagger.v3.core.converter.ModelConverters` -- swagger-core-jakarta 2.2.55 (brought in by springdoc)
- `management.server.port`, `management.endpoints.web.exposure.include`, `management.endpoint.health.probes.enabled`, `management.endpoint.health.show-details`, `management.endpoint.health.roles` -- spring-boot-actuator-autoconfigure / spring-boot-health 4.1.2 metadata
- `SEED_API_DOCS_ENABLED` -- new environment variable, documented in `.env.example`
- SLF4J 2 fluent key-value API (`addKeyValue`), rendered as members by Boot's ECS formatter -- slf4j-api 2.x and spring-boot 4.1.2
- `OncePerRequestFilter#shouldNotFilterErrorDispatch` and `#shouldNotFilterAsyncDispatch` (both default to skipping), overridden to return `false` -- Spring Framework 7

## Tasks & Acceptance

**Execution:**
- [x] `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts` -- add `springdoc = "3.2.0"`, the actuator starter and the Prometheus registry. Add a system property `seed.openapi.output=build/openapi/api.yaml` on `integrationTest` and declare that file as a task output. Add `checkOpenApi` (depends on `integrationTest`; fails on a byte difference with `openapi/api.yaml`, naming `updateOpenApi`; `check` depends on it) and `updateOpenApi` (copies the generated file over the committed one).
- [x] `common/ids/IdGenerator.java` -- `@Component`. `UUID newId()` builds a UUIDv7: 48-bit Unix milliseconds from `Clock`, version 7, variant 2, random bits from `SecureRandom`.
- [x] `common/logging/CorrelationIdFilter.java` -- `OncePerRequestFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE + 10)`; validation, MDC handling, header and re-dispatch behaviour as in Always. Store the ID in a request attribute.
- [x] `common/logging/RequestLoggingFilter.java` -- ordered right after the correlation filter. Writes the summary line from `BEST_MATCHING_PATTERN_ATTRIBUTE`, skips `/actuator/**`, and follows the "logged once, with the final status" rule from Always, keeping the start time in a request attribute.
- [x] `common/logging/LogSafe.java` -- `clean(String)`, final utility class.
- [x] `common/logging/SensitiveMemberMaskingCustomizer.java` -- the masking customizer from Always, with a public no-arg constructor.
- [x] `config/OpenApiConfig.java` -- `OpenAPI` bean (title `Seed API`, version `v1`, server `/`), plus an `OpenApiCustomizer` that registers the four envelope schemas through `ModelConverters`.
- [x] `application.yml`, new `application-local.yml` and `application-dev.yml`:
  - `application.yml`: ECS settings, the customizer class, the springdoc flags (`writer-with-order-by-keys: true`), the management port, exposure and health settings.
  - `local`: sets `logging.structured.format.console: ""` and `logging.pattern.correlation: "[%X{correlationId:-}] "`.
  - `local` and `dev`: `${SEED_API_DOCS_ENABLED:true}`.
- [x] `backend/openapi/api.yaml` -- generate with `./gradlew updateOpenApi` and commit it.
- [x] `.env.example` -- add a commented-out `# SEED_API_DOCS_ENABLED=false`. The comment says `local`/`dev` default it to `true`, and prod must leave it unset or `false`.
- [x] `_snap-output/implementation-artifacts/deferred-work.md` -- append one `source_spec` entry per deferred item in Never.
- [x] Tests:
  - `IdGeneratorTest` and `LogSafeTest` (unit);
  - `CorrelationIdIT`: ID, error-path, masking, no-leak and summary rows, with `OutputCaptureExtension` and the probes;
  - `OpenApiExportIT`: fetches `/v3/api-docs.yaml` with the docs flag on, checks the contract-content row and writes the output file;
  - `ApiDocsDisabledIT`: covers both `/v3/api-docs` and Swagger UI;
  - `ActuatorIT`, using `@LocalManagementPort`.

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew clean build` runs, then it passes and leaves `openapi/api.yaml` unchanged.
- Given the ERROR log of an unexpected 500 in ECS output, when it is parsed as JSON, then it has `correlationId`, `log.level` `ERROR` and `service.name` `seed`.
- Given the module rules, when `ModularityTest` and `ArchitectureTest` run, then they pass with the new packages.

## Implementation Notes

- Planning: two critic passes (14 then 11 findings), all resolved by spec edits the user approved. A third critic pass was skipped at the user's choice on 2026-11-16 ("apply your recommendation", which included proceeding without one).

- Implementation (subagent): all tasks done. Choices it made beyond the task list:
  - `integrationTest` uses `forkEvery = 1`, because Boot sets up logging once per JVM and the ECS and `local` log-capture tests need separate JVMs;
  - `GlobalExceptionHandlerTest` (story 4) now parses ECS JSON lines and excludes the two new filters from its `@WebMvcTest`;
  - test-only `ProbeController` gained `/probe/async/{outcome}` for the async summary rule;
  - a `LocalReadableLogIT` covers the `local` row.
- Known limits it reported:
  - an envelope built on an async executor thread has `correlationId: null` until story 9's `TaskDecorator` (the header is correct);
  - no summary line is written when an exception escapes and Tomcat never dispatches to `/error` (client abort);
  - future `@WebMvcTest` slices pick up `CorrelationIdFilter` and need `IdGenerator` or an exclude.
- Main agent, in Verify:
  - replaced literal invisible U+2028/U+2029 characters in `LogSafe` and `LogSafeTest` with `\u2028`/`\u2029` escapes;
  - the drift matrix row has no automated test; it was checked by hand with the Verification command (`checkOpenApi` failed with the `updateOpenApi` message, and passed after restoring the file).
- Verify: `./gradlew clean build` passes with 190 tests in 15 suites, 0 failures; `openapi/api.yaml` is unchanged by the build. snap-guard: 0 findings; its opengrep, lizard and scc layers did not run (not installed).

- Review pass 1 patches P1–P10 were applied by the implementation subagent (re-engaged): suffix masking; normalised `/actuator` path; structured summary key-values (`http.request.method`, `http.route`, `http.response.status_code`, `event.duration`); MDC kept for the container's ERROR line; contract-file lifecycle in Gradle; test environment isolation; `forkEvery` replaced by a separate `localProfileTest` task; `DevApiDocsIT` plus the `local` docs assertion; bidi controls in `LogSafe`; README. It also added the test-only `fixtures/web/StatusProbe` helper to clear a snap-guard duplication finding. Deferrals D1–D4 were appended to deferred-work.md.
- Final verification (main agent): `./gradlew clean build` passes, 204 tests in 17 suites with 0 failures (12.5 s). The build also passes with `SPRING_PROFILES_ACTIVE=local` and `SEED_API_DOCS_ENABLED=true` exported. `openapi/api.yaml` is unchanged by the build, and the manual drift check still fails with the `updateOpenApi` message and passes after restoring. snap-guard: 0 findings across 28 files. Doubt scan: clean.

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 38 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | slop | Redundant explicit no-arg constructor in `SensitiveMemberMaskingCustomizer` | low | No other constructor exists, so Java's default constructor is identical; the comment repeats the Javadoc. Fix is a deletion. | patch (P1) |
| R2 | security | Prometheus open to anonymous callers on 8081 (all interfaces) | medium | No Spring Security yet; `ActuatorIT` reads `/actuator/prometheus` anonymously. security.md §3 denies non-health actuator endpoints to anonymous callers. Securing it is story 7's job by intent (security is out of this story). | defer (D1) |
| R3 | security | Docs and Swagger UI unauthenticated in `dev`/`local` | medium | True by the approved design (spec line 32, "Story 7 puts the dev docs behind authentication"); no deferred entry tracked it. | defer (D1) |
| R4 | security | `local` profile does not mask | false | Accepted gap written into the frozen spec (Always: "Readable local lines are not masked, an accepted gap"). | rejected |
| R5 | security | Exact-name masking misses `accessToken`, `client_secret`, `x-api-key`, ... | medium | `SENSITIVE_NAMES.contains(name.toLowerCase())` only matches the exact last segment; errors-and-logging.md §4 forbids logging refresh tokens and keys. Suffix matching after normalising is a superset of the spec's list. | patch (P1) |
| R6 | security | `/actuator` skip uses the raw URI, so `/actuator/..;/x` can avoid the summary line | medium | `isActuator` uses `getRequestURI()` (raw, not normalised), while Tomcat routes on the normalised servlet path. | patch (P2) |
| R7 | security | Clients pick their own correlation ID, including `job:`-shaped IDs | low | By design: api-standards.md §8 accepts any `[A-Za-z0-9._:-]{1,64}`; the regex blocks injection. Unlikely to matter now, and the fix would change the contract. | rejected |
| R8 | security | UUIDv7 reveals creation time | false | Inherent to UUIDv7, chosen in decisions.md; 74 random bits keep it unguessable. | rejected |
| R9 | security | `show-details: when-authorized` / ADMIN has no effect until story 7, and management-port security is untracked | medium | No security on the classpath, so details are never shown (safe today); securing the management port as a whole is not in deferred-work. | defer (D1) |
| R10 | security | Only an env var protects prod from docs | low | The code default is `false` (tested by `ApiDocsDisabledIT`); a prod guard would add a profile file and a startup check. Unlikely to be met. | rejected |
| R11 | verification-gap | `local`/`dev` docs defaults untested | medium | Pre-verified gap: no test activates `dev`, and `LocalReadableLogIT` never requests docs. | patch (P8) |
| R12 | verification-gap | `checkOpenApi` verified only by hand | medium | Pre-verified gap; closing it needs Gradle TestKit, which the repo does not use. Filed disposition: defer. | defer (D3) |
| R13 | blind | api-standards.md §7 `OperationCustomizer` (wrap 2xx as `ApiResponse<T>`, add error responses) neither built nor deferred | medium | No endpoints exist yet, so `api.yaml` is unaffected today; the tracking gap is real, and the first endpoint (story 8) would export unwrapped operations. | defer (D2) |
| R14 | blind | Contract lacks the `X-Correlation-Id` header and `required` lists; `data`/`rejectedValue` untyped | low | True, but no operations exist to carry the header, and no generated client exists yet; folded into D2's note. Fix (schema annotations on the records) is more than a direct correction. | rejected (noted in D2) |
| R15 | blind + edge | `checkOpenApi` may compare a stale or missing `build/openapi/api.yaml`; `updateOpenApi` gives a raw exception when it is missing | low | The file is only written by `OpenApiExportIT`; a filtered `integrationTest` leaves the old file. Fix is direct: delete at task start, and give a clear error when missing. | patch (P5) |
| R16 | edge | `seed.openapi.output` hard-coded while the task reads `layout.buildDirectory` | low | A relocated build directory would split the two paths. Direct correction: pass the provider's absolute path. | patch (P5) |
| R17 | blind | The actuator skip is dead on 8081 (main-context filters do not run on the management server) and the 8081 half of the test passes vacuously | low | Correct about 8081; the app-port half of `actuatorRequestsAreNotSummarised` does exercise the skip. Same root and fix as R6. | patch (P2) |
| R18 | blind + edge | No summary line when an exception escapes and no ERROR dispatch follows (client abort) | low | Real but rare: handler exceptions are handled by `GlobalExceptionHandler`, so it needs a filter-level exception plus an aborted dispatch. The fix adds a request listener. | rejected |
| R19 | blind | Summary fields only in the message text, not structured members | low | Operators must regex the message to filter by status or route, every day; adding key-values is a direct change. | patch (P3) |
| R20 | blind | Dev docs auth and health-details ADMIN not tracked in deferred-work | medium | Confirmed against the 16 new entries: neither is listed. Same root as R2/R3/R9. | defer (D1) |
| R21 | blind | No prod guard beyond a comment | low | Same as R10. | rejected |
| R22 | blind + edge | Tests depend on the shell environment (`SEED_API_DOCS_ENABLED`, `SPRING_PROFILES_ACTIVE=local`) | medium | `.env.example` sets `SPRING_PROFILES_ACTIVE=local`; a developer who exports it gets readable logs, and `CorrelationIdIT`/`GlobalExceptionHandlerTest` (which parse JSON) fail. | patch (P6) |
| R23 | blind | `forkEvery = 1` on every IT | medium | Only `LocalReadableLogIT` needs its own JVM; per-class forking defeats the test-context cache and the per-JVM Testcontainers reuse that quality-and-delivery.md §1 relies on from story 6. | patch (P7) |
| R24 | blind | README out of date (`integrationTest` "none yet", no `checkOpenApi`/`updateOpenApi`, port 8081, `local` logs, `SEED_API_DOCS_ENABLED`) | low | Confirmed; a direct text correction. | patch (P10) |
| R25 | blind | ECS lines lack environment and version | low | Not required by errors-and-logging.md §4; adding a version needs build-info wiring. | rejected |
| R26 | blind | `IdGenerator` has no ordering within one millisecond and no clock-backwards guard | low | Irrelevant for correlation IDs; matters only when story 6 uses it for primary keys. | defer (D4) |
| R27 | edge | Tomcat's own ERROR line for a filter exception has no `correlationId` | medium | Confirmed in the `CorrelationIdIT` output: `Servlet.service() ... threw exception` at ERROR with `correlationId` null, because `CorrelationIdFilter`'s `finally` clears MDC before `StandardWrapperValve` logs. | patch (P4) |
| R28 | edge | `LogSafe` lets bidi controls (U+202A–U+202E, U+2066–U+2069) through | low | They can visually reorder a viewed log line; adding two ranges is a direct correction and a superset of the spec's list. | patch (P9) |

## Design Notes

**Why restore on re-dispatch:** an exception that leaves the filter chain is dispatched to `/error` only after the original `finally` has cleared MDC. Without the request attribute, the `/error` envelope's `correlationId` would be null, or worse, a different ID.

**Why a fixed server URL:** springdoc otherwise writes `http://localhost:<random port>`, so every run would show drift.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` -- expected: BUILD SUCCESSFUL, all suites green, `checkOpenApi` passes.
- `git status --porcelain backend/openapi` after the build -- expected: empty, apart from the first add.
- Edit one character in `backend/openapi/api.yaml`, run `./gradlew checkOpenApi`, then revert -- expected: the task fails and names `updateOpenApi`.

## Tech Spec Critic Findings

- [SOLVED] [medium] check-1 | spec line 110 | `.env.example` sets `SEED_API_DOCS_ENABLED=false` next to `SPRING_PROFILES_ACTIVE=local`, so a developer who loads that file gets an explicit `false` that overrides the `${SEED_API_DOCS_ENABLED:true}` default in `local`/`dev`; Swagger UI is then off locally, contradicting CAP-15 and the comment the spec asks for.
- [SOLVED] [medium] check-1 | spec line 36 | CAP-19 requires the metrics in quality-and-delivery.md §10 (`hikaricp_*`, `seed_job_duration_seconds`, `seed_ws_sessions_active`, `seed_operation_total`, `seed.storage.*`), but the spec delivers only `http_server_requests_*` and JVM metrics and does not list the others as deferred.
- [SOLVED] [medium] check-5 | spec line 27 | When the ERROR re-dispatch writes the summary line, "route template" can be read as the original route (or `UNMATCHED`) or as the `/error` mapping matched during re-dispatch.
- [SOLVED] [medium] check-5 | spec line 27 | "Normally the original dispatch writes it" does not cover ASYNC requests; the original dispatch ends before the response completes, so the line could carry a provisional status and a short duration.
- [SOLVED] [low] check-1 | spec line 36 | CAP-15's frontend client-drift check (`frontend/src/app/api/`) is neither covered nor listed as deferred to a frontend story.
- [SOLVED] [low] check-3 | spec line 33 | "Actuator exposes only `health` and `prometheus` on 8081" has no matrix row showing other endpoints (e.g. `/actuator/env`) are unreachable or that `/actuator/*` is not served on the application port.
- [SOLVED] [low] check-3 | spec line 28 | The `local` profile's readable lines with `[<correlationId>]` have no matrix row.
- [SOLVED] [low] check-5 | spec line 26 | "runs before any other application filter" may or may not include Boot's framework filters (e.g. the observation filter), which changes whether their output carries the correlation ID.
- [SOLVED] [low] check-5 | spec line 51 | "invalid value never logged raw" reads either as "logged only through `LogSafe`" or "never logged at all".
- [SOLVED] [low] check-5 | spec line 57 | The LogSafe input shown as `"a b"` contains an invisible U+2028, so a reader takes it as a plain space, contradicting line 30.
- [SOLVED] [low] check-6 | spec line 78 | `## Things this relies on` omits the SLF4J 2 fluent key-value API (`addKeyValue`) with Boot's ECS rendering of key-value pairs, and `OncePerRequestFilter`'s ERROR/ASYNC re-dispatch hooks (`shouldNotFilterErrorDispatch`/`shouldNotFilterAsyncDispatch`).
