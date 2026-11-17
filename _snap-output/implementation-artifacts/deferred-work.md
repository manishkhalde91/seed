# Deferred work

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/1-repository-skeleton-local-infrastructure-module-boundaries.md`
  summary: Add an automated check that `seed_app` cannot run DDL and can read/write tables created by `seed_owner`, reusing `infrastructure/postgres/init/01-roles.sh`.
  evidence: Verification-gap review pass 1: removing `REVOKE CREATE ON SCHEMA public FROM PUBLIC` or the default-privileges lines leaves every test green; no DB test harness exists until the persistence story (story 5) adds Testcontainers.

- source_spec: none
  summary: Story 4 — response envelope, exception handling and validation (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: the user is building backend stories one per pass; story 2 (Gradle migration) goes first because every later story is verified with `./gradlew clean build`.

- source_spec: none
  summary: Story 5 — correlation ID, structured logging, OpenAPI export and health (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: none
  summary: Story 6 — persistence foundation: Flyway, auditing, soft delete, IDs, idempotency (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: none
  summary: Story 7 — security: OIDC resource server, RBAC and CORS (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: none
  summary: Story 8 — sample feature backend (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: none
  summary: Story 9 — live operation status over WebSocket (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: none
  summary: Story 10 — object storage and sample export download (spec-monorepo-seed/stories.yaml).
  evidence: Work-packet split: one backend story per pass, after story 2.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/2-migrate-backend-build-from-maven-to-gradle.md`
  summary: Update stack.md "Known compatibility risks" item 4 to record the approved enforcedPlatform() + failOnVersionConflict() approach instead of "narrow it to the runtime and test classpaths".
  evidence: Review triage #17 — narrowing was tried in story 2 and still fails on the Boot BOM's own resolution; the human approved enforcedPlatform() on 2026-11-14, so the planning doc now disagrees with the build.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/2-migrate-backend-build-from-maven-to-gradle.md`
  summary: Add Gradle dependency verification (gradle/verification-metadata.xml) for downloaded dependencies and plugins.
  evidence: Review triage #18 — only the Gradle distribution is checksum-pinned; pre-existing, the Maven build had no artifact verification either.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/2-migrate-backend-build-from-maven-to-gradle.md`
  summary: Validate the committed gradle-wrapper.jar automatically in CI (e.g. gradle/actions/wrapper-validation) in story 3.
  evidence: Review triage #19 — the jar matches the published sha256 3b8a2577… today, but nothing would catch a tampered jar in a future PR.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/2-migrate-backend-build-from-maven-to-gradle.md`
  summary: In CI (story 3), fail when ArchitectureTest or ModularityTest did not run in the test task (e.g. assert TEST-*ArchitectureTest.xml and TEST-*ModularityTest.xml exist with tests > 0).
  evidence: Review triage #20 — a future change to the test include/exclude wiring could drop them while ./gradlew build stays green.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/2-migrate-backend-build-from-maven-to-gradle.md`
  summary: Add negative-path checks for the build gates (unmanaged version conflict, missing JDK 21 toolchain, unformatted Java file), e.g. a CI job or documented probe commands.
  evidence: Review triage #21 — each gate was proven only by manual probes during story 2; removing failOnVersionConflict() would keep today's build green.

- source_spec: none
  summary: Map the Spring Security and rate-limit rows of errors-and-logging.md §2 (AccessDeniedException, AuthorizationDeniedException → 403 FORBIDDEN; AuthenticationException → 401 UNAUTHENTICATED; RateLimitExceededException → 429 RATE_LIMITED) in GlobalExceptionHandler during story 7.
  evidence: Story 4 scope decision (human, 2026-11-14) — Spring Security is not on the classpath until story 7, so story 4 maps only exceptions whose types exist.

- source_spec: none
  summary: Map the persistence rows of errors-and-logging.md §2 (ObjectOptimisticLockingFailureException → 409 CONCURRENT_MODIFICATION; DataIntegrityViolationException → mapped 409 / 500 DATA_INTEGRITY_VIOLATION; other DataAccessException → 503/500) in GlobalExceptionHandler during story 6.
  evidence: Story 4 scope decision (human, 2026-11-14) — Spring Data/JPA is not on the classpath until story 6.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/4-response-envelope-exception-handling-and-validation.md`
  summary: Add PageQuery with per-endpoint sort whitelisting (unknown sort field → 400 VALIDATION_FAILED) in story 8, when the first paged endpoint exists.
  evidence: Story 4 ships PageResult and ApiResponses.page() only; no endpoint needs PageQuery yet.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/4-response-envelope-exception-handling-and-validation.md`
  summary: Create docs/validation-rules.md (field → constraint → frontend validator) with the first real request DTO in story 8.
  evidence: Story 4 has no production DTOs; parity has nothing to list yet.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/4-response-envelope-exception-handling-and-validation.md`
  summary: In story 6, map ConstraintViolationException to 400 VALIDATION_FAILED only when the violation comes from a controller (root bean is a @RestController); internal service or JPA entity validation failures must become 500 INTERNAL_ERROR without echoing paths or values.
  evidence: Review triage S1/B3 (maybe-false, medium if true) — no internal validation exists today; settles when story 6 adds Hibernate entity validation and a test persists an invalid entity.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/4-response-envelope-exception-handling-and-validation.md`
  summary: In story 7 (request limits), add the request body size filter and multipart limits so oversize JSON and uploads return 413 PAYLOAD_TOO_LARGE through ApiErrorResponseWriter.
  evidence: Review triage S10 — only MaxUploadSizeExceededException maps to 413 today and no body size limit is configured.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/4-response-envelope-exception-handling-and-validation.md`
  summary: In story 9, implement the STOMP @MessageExceptionHandler mapping to ERROR frames and the async-listener failure handling (retry up to 3, then mark the operation FAILED) from errors-and-logging.md §2.
  evidence: Review triage B14 — both §2 rules are outside story 4 and were not recorded anywhere.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 6, add readiness health checks for the database and the Flyway schema to the readiness group (management.endpoint.health.group.readiness).
  evidence: Story 5 Never list — the readiness probe reports only Boot's readinessState until a database exists.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 7, add `user.id` (the token subject, or `system:*` for background work) to every log line, e.g. as an MDC entry set after authentication.
  evidence: Story 5 Never list — errors-and-logging.md §4 requires it, but there is no authentication until story 7.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 7, expose `/actuator/loggers` on the management port to ADMIN only, so DEBUG can be toggled per logger (errors-and-logging.md §4).
  evidence: Story 5 Never list — exposure is health and prometheus only, because nothing can restrict an endpoint to ADMIN before story 7.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, add `operationId` to log lines when it is known (errors-and-logging.md §4).
  evidence: Story 5 Never list — no operations exist before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, add the async executor `TaskDecorator` that copies the MDC (correlationId) and the security context into listeners and async work.
  evidence: Story 5 Never list — today an envelope built on an executor thread (e.g. a `Callable` handler) has `correlationId: null`; CorrelationIdIT notes this on its async probe.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, take the correlation ID from the STOMP CONNECT frame (`X-Correlation-Id`) and carry the causing change's ID on pushes (errors-and-logging.md §4, rule 4).
  evidence: Story 5 Never list — no WebSocket/STOMP endpoint exists before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, open a new correlation ID per scheduled job run (`job:<name>:<uuid>`).
  evidence: Story 5 Never list — no scheduled jobs exist before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, add a readiness check for the PostgreSQL LISTEN connection.
  evidence: Story 5 Never list — the LISTEN listener arrives with live operation status in story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In a frontend story, add the Angular HTTP interceptor that sends `X-Correlation-Id` on every request (frontend-architecture.md §6).
  evidence: Story 5 Never list — backend-only story; the backend already accepts and echoes the header.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In a frontend story, add the drift check for the generated API client in `frontend/src/app/api/` against `backend/openapi/api.yaml` (CAP-15).
  evidence: Story 5 Never list — story 5 adds only the backend side (`checkOpenApi`/`updateOpenApi`).

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 6, verify the `hikaricp_*` metrics appear on `/actuator/prometheus` once the datasource exists.
  evidence: Story 5 Never list (CAP-19, quality-and-delivery.md §10) — no datasource before story 6.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, publish the `seed_job_duration_seconds` metric.
  evidence: Story 5 Never list (CAP-19, quality-and-delivery.md §10) — no jobs before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, publish the `seed_ws_sessions_active` metric.
  evidence: Story 5 Never list (CAP-19, quality-and-delivery.md §10) — no WebSocket sessions before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 9, publish the `seed_operation_total` metric.
  evidence: Story 5 Never list (CAP-19, quality-and-delivery.md §10) — no operations before story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In the storage story, publish the `seed.storage.*` metrics.
  evidence: Story 5 Never list (CAP-19, quality-and-delivery.md §10) — no object storage client before the storage story.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: When a trace backend exists, enable Micrometer Tracing / OTLP export (`management.otlp.tracing.endpoint`) so log lines carry `traceId` and `spanId`.
  evidence: Story 5 Never list — OTLP tracing stays off; there is no trace backend yet.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 7, secure the management port and the API docs per security.md §3: deny anonymous access to every Actuator endpoint except liveness and readiness (today `/actuator/prometheus` on 8081 answers anyone), make `show-details: when-authorized` / `roles: ADMIN` actually take effect on the management port's own filter chain, and put `/v3/api-docs` and Swagger UI behind authentication in `dev`.
  evidence: Story 5 review R2/R3/R9/R20: no Spring Security exists yet, so `ActuatorIT` reads Prometheus anonymously and the dev docs are open; the spec deferred only `/actuator/loggers`.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: With the first real endpoint (story 8), add the api-standards.md §7 `OperationCustomizer` that wraps every 2xx schema as `ApiResponse<T>` and adds `ApiErrorResponse` for 400/401/403/404/409/422/500. Also document the `X-Correlation-Id` request and response header, and give the envelope schemas `required` lists and typed `data`.
  evidence: Story 5 review R13/R14: `OpenApiConfig` only registers the four envelope schemas as loose components and `api.yaml` has `paths: {}`, so the gap is invisible until an endpoint exists.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: Add an automated test (Gradle TestKit or an equivalent) proving `checkOpenApi` fails when the committed `backend/openapi/api.yaml` differs from the generated one.
  evidence: Story 5 review R12 (verification-gap): the drift gate was verified only by hand; an inverted comparison would keep every build green.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/5-correlation-id-structured-logging-openapi-export-and-health.md`
  summary: In story 6, before `IdGenerator` creates primary keys, decide on RFC 9562 §6.2 monotonicity within one millisecond (counter or random increment) and on a clock-moving-backwards guard, and document the choice.
  evidence: Story 5 review R26: IDs from the same millisecond sort randomly; harmless for correlation IDs, but it costs B-tree locality and creation-order sorting for keys.

- source_spec: none
  summary: Story 6c — idempotency: the `Idempotency-Key` filter (same key and body replays the stored response; same key with a different body → 409 `IDEMPOTENCY_KEY_REUSED`), `common.idempotency_record` (bigint identity PK, 24 h retention) with `IdempotencyRecordRepository` + JPA implementation, and its hourly cleanup (api-standards.md §1, data-and-migrations.md §2, realtime-and-storage.md jobs table).
  evidence: Story 6 split (human, 2026-11-16, answer "S"): 6a database/migrations and 6b auditing/soft delete/IDs are built together first; idempotency is an independent shippable slice built in the next pass.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 7, resolve `CurrentActor` as the open `SystemActor` scope, then the authenticated token's `user:<sub>`, then `anonymous` (data-and-migrations.md §4); today `DefaultCurrentActor` has no user step, so requests are audited as `anonymous`.
  evidence: Story 6 decision (option a, 2026-11-16) — no authentication exists before story 7.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 9, add the Spring Modulith `event_publication` table to the `common` schema with its own migration (data-and-migrations.md §1, §7).
  evidence: Story 6 Never list — the event publication registry arrives with the first published events.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In the storage story, add the ArchUnit rule that `@Transactional` classes do not call ports marked `@External` (data-and-migrations.md §7), with a violating and a compliant fixture.
  evidence: Story 6 Never list — `@External` does not exist before the storage story.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 8, enforce that audit fields (`created_*`, `updated_*`, `deleted_*`) are never part of a request DTO (data-and-migrations.md §4, CAP-6; update requests carry only `version`, for the §6 check), e.g. an ArchUnit or reflection check over the request records.
  evidence: Story 6 Never list — no request DTOs exist before story 8.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 8, decide how feature entities tell Spring Data that an app-assigned UUIDv7 entity is new (the story 6 fixture implements `Persistable` with `isNew() = createdAt == null`); without it, `save()` of a new entity goes through `merge` because the ID is set and `version` is a primitive.
  evidence: Story 6 build — `AuditableEntity` follows data-and-migrations.md §4 exactly (primitive `long version`, no `Persistable`), so the choice is left to the first real entity.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 9, when scheduled jobs and async/Modulith listeners arrive, (1) keep schedulers and listeners from starting in the `migrate` profile, (2) make non-web callers log SQL failures with their SQLSTATE (the `SqlExceptionHelper` logger is off because the web handler logs each failure once), and (3) carry the `SystemActor` scope into executor threads alongside the MDC in the `TaskDecorator`.
  evidence: Story 6 review R18/R25/R29: no jobs, listeners or executors exist yet, so none of these can fail today; each becomes real with story 9's async work.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6-persistence-foundation-flyway-auditing-soft-delete-ids.md`
  summary: In story 6c, exempt the `common.idempotency_record` retention cleanup (a hard delete on an internal, non-soft-deletable table) from the no-`delete`/`remove` ArchUnit rule, e.g. by scoping the rule to repositories of `SoftDeletableEntity` types or an explicit allow-list.
  evidence: Story 6 review R19: the rule now forbids every `delete*`/`remove*` and `@Query("DELETE …")` on Spring Data interfaces, which the 24 h cleanup needs.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6c-idempotency.md`
  summary: In story 7, scope `Idempotency-Key` records to the authenticated caller (store the subject with the key and match on both), so one user can never replay another user's stored response by guessing or reusing their key.
  evidence: Story 6c Never list — no authentication exists before story 7; today keys are global.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6c-idempotency.md`
  summary: In story 9, move `IdempotencyCleanup` (today a plain `@Scheduled` method) into `common.jobs.ScheduledJob`, so it takes the PostgreSQL advisory lock (one instance runs it), opens a `job:idempotency-cleanup:<uuid>` correlation ID and publishes job metrics.
  evidence: Story 6c decision (option a, 2026-11-16) — `ScheduledJob` arrives with story 9.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6c-idempotency.md`
  summary: In story 7, order `IdempotencyFilter` AFTER the Spring Security filter chain (and any rate limiter), not just after `RequestLoggingFilter`. Then replays pass authentication and `@PreAuthorize` and carry the security headers, 401/403/429 answers are never stored for 24 h, and the authenticated subject is available for the per-caller key scoping already deferred.
  evidence: Story 6c review R1: `IdempotencyFilter.ORDER = HIGHEST_PRECEDENCE + 12` runs ahead of Spring Security's default chain (-100), so once security exists a replay returns before authentication.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/6c-idempotency.md`
  summary: In the storage story, decide how idempotent multipart uploads work; today keyed `multipart/form-data` and form-urlencoded writes pass through the idempotency filter untouched, because the container parses parameters and parts from a stream the filter would have drained.
  evidence: Story 6c review R4: re-serving the raw body cannot restore `getParameter*`/`getParts`; uploads are the first multipart writes.

- source_spec: none
  summary: Story 7b — request limits (security.md §6): JSON bodies up to 1 MB and multipart limits answered 413 `PAYLOAD_TOO_LARGE` through `ApiErrorResponseWriter`, and a per-user write rate limit of 60/min answered 429 `RATE_LIMITED` with `Retry-After` (`RateLimitExceededException` row of errors-and-logging.md §2); both configurable.
  evidence: Story 7 split (human, 2026-11-16, answer "S"): 7a (authentication, authorization and the token-dependent deferred items) is built first; request limits are an independent shippable slice built in the next pass.

- source_spec: none
  summary: Story 9b — live operation status over WebSocket (realtime-and-storage.md §1.2–§1.3, api-standards.md §9): STOMP on `/ws` with JWT on CONNECT, subscription authorisation (owner or ADMIN, max 50 per session), snapshot `@SubscribeMapping`, ERROR frames (`@MessageExceptionHandler`), push-only (`SEND /app/**` rejected), token-expiry check every 30 s and close 1012 on shutdown, correlation ID from CONNECT, `PgStatusListener` (dedicated LISTEN connection, 1–30 s reconnect backoff, `/topic/system/resync`), its readiness check, `seed_ws_sessions_active`, origin check, and a two-instance fan-out test.
  evidence: Story 9 split (human, 2026-11-17, answer "S"): 9a (jobs/async foundation, operations, sample export start) ships first with the REST snapshot as the status channel; the WebSocket push is an independent slice built in the next pass.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/9-operations-jobs-and-sample-export.md`
  summary: In story 10, stream the sample export to object storage (page or stream the active records and write the CSV as it is read) instead of loading every record and the whole file into memory.
  evidence: Story 9a review R15: `SampleExportListener` calls `findAllActive()` and builds the CSV in a `StringBuilder`, up to 8 exports at once; in 9a the file is discarded after counting, so the memory cost only matters once story 10 keeps it.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/9-operations-jobs-and-sample-export.md`
  summary: If a second OIDC issuer or tenant is ever trusted, bind actor IDs to the issuer (for example `user:<iss>|<sub>`), so ownership checks such as an operation's `requested_by` cannot match across issuers.
  evidence: Story 9a review R34 (low): `SecurityContextActor` builds `user:<sub>` (story 7) and `OperationService.getForCurrentActor` compares it; safe with today's single issuer.

- source_spec: `_snap-output/specs/spec-monorepo-seed/stories/9b-live-operation-status-over-websocket.md`
  summary: Add WebSocket abuse limits to `/ws`: a cap on STOMP sessions per user (and per address before CONNECT), a rate limit on handshakes/CONNECT and snapshot SUBSCRIBEs, and bounded queues on the client inbound/outbound channel executors.
  evidence: Story 9b review R23: only the per-session 50-subscription cap exists; one token can open any number of sessions, each SUBSCRIBE costs a database read, and the channel pools queue without bound; the REST write limiter (story 7b) has no WebSocket counterpart.
