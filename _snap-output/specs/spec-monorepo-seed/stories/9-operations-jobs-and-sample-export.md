---
title: 'Story 9 (9a) — Jobs and async foundation, operations, sample export'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_commit: 'c3d419171a8de0cc092c8f6a89011f66ae3da82f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/realtime-and-storage.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/architecture-diagrams.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Long-running work has no home. There is no operation model, no async listeners with context propagation, no event registry, and no job wrapper that runs a job on one instance only. The sample cannot start its export (CAP-10, the 9a slice). Twelve deferred items wait for this foundation.

**Approach:** Packet 9a (decided 2026-11-17, answer "S"; 9b WebSocket push is deferred).

- **Jobs and async:** a bounded async executor whose `TaskDecorator` carries the MDC, the security context and the `SystemActor` scope; a `ScheduledJob` wrapper (advisory lock, system scope, job correlation ID, metric).
- **Operations:** the `common.operation` model and service (conditional status updates plus `pg_notify`), the REST snapshot `GET /api/v1/operations/{id}`, listener retry-then-FAILED, and the cleanup jobs.
- **Events:** the Spring Modulith event-publication registry in `common`.
- **Sample:** the export endpoint, whose listener builds the CSV in memory and completes with `{rowCount}` (decided: option a; story 10 stores the file).

## Boundaries & Constraints

**Always:**
- **Async executor:** `@EnableAsync` (in `config/AsyncConfig`); Boot's `applicationTaskExecutor`, bounded (`spring.task.execution.pool.*`: core 4, max 8, queue 200). A `TaskDecorator` bean copies the submitting thread's MDC (`correlationId`, `user.id`, `operationId`), its `SecurityContext` and its `SystemActor` scope into the task, and clears them afterwards.
- **Event registry:** `spring-modulith-starter-jdbc` with `spring.modulith.events.jdbc.schema: common` and schema initialisation off. A Flyway migration creates `common.event_publication` (the Modulith 2.1 v2 PostgreSQL layout). Unfinished publications are resubmitted on restart, except in `migrate`.
- **`common.operation`:** columns as in realtime-and-storage.md §1.1: `status_version smallint`, `result jsonb`, `requested_by`, `correlation_id`, `error_code`, `error_message`, and audit `created_*`/`updated_*`. CHECK on `status`; index on `(requested_by, created_at)`. The repository follows the Repository Pattern under `common.operations`.
- **`OperationService`:**
  - `start(kind, subjectId)` inserts `QUEUED`, version 1, `requested_by = CurrentActor.id()`, `correlation_id` from MDC, in the caller's transaction.
  - `markRunning`, `complete(result)` and `fail(code, safeMessage)` each run one conditional `UPDATE … SET status = :to, status_version = status_version + 1 WHERE id = :id AND status_version = :expected AND status IN (:allowedFrom)`, plus `pg_notify('operation_status', <push JSON>)` in the same transaction. The JSON is api-standards.md §9's payload, extended with `errorCode` and `errorMessage` (null unless `FAILED`; §9 is updated), and stays under 8,000 bytes (`error_message` is cut to fit).
  - Allowed transitions are architecture-diagrams.md §4. A lost conditional update or an illegal transition → `INVALID_STATE_TRANSITION` (409).
  - `seed_operation_total{kind,status}` is incremented on every terminal transition.
- **Listener rules:**
  - An `@ApplicationModuleListener` handling operation work runs inside `SystemActor.run(event.requestedBy(), …)` with MDC `correlationId` (from the event) and `operationId`.
  - It accepts the state it finds, because a restart or a resubmission can deliver the event again:
    - `QUEUED` → `markRunning`, then the work;
    - `RUNNING` → the work, without `markRunning` again;
    - `SUCCEEDED`/`FAILED` → nothing.
  - Its work runs once and is retried up to 3 times (4 attempts) with exponential backoff (Spring Framework 7 `RetryTemplate`, 1 s × 2). Permanent failures (`SeedException`s such as `INVALID_STATE_TRANSITION` or a business rule) are not retried. After the last failure the operation is `fail`ed with a safe code and message (`OPERATION_FAILED`, "The operation failed") and the error is logged once at ERROR with its SQLSTATE when there is one. The listener then returns normally, so the publication completes.
- **`GET /api/v1/operations/{id}`:**
  - Allowed for the operation's `requested_by` actor or `ADMIN`. Anyone else → 403 `FORBIDDEN` (`ForbiddenOperationException`); unknown id → 404 `OPERATION_NOT_FOUND`.
  - Enveloped data: `{operationId, kind, subjectId, status, version, result, errorCode, errorMessage, createdAt, updatedAt, correlationId, statusTopic}`, where `statusTopic` = `/topic/operations/{id}`.
- **`ScheduledJob`** (`common.jobs`): `run(name, Runnable)` used inside each `@Scheduled` method.
  - Takes `pg_try_advisory_lock(hashtext('job:' || name))` on one dedicated connection held for the run; if busy, it skips (DEBUG) and returns.
  - Opens `SystemActor.run("system:job:" + name, …)` and MDC `correlationId = job:<name>:<uuidv7>`. The 64-character format in api-standards.md applies to client-supplied IDs only, so job IDs may be longer.
  - Logs start and end at INFO and records `seed_job_duration_seconds{job,status=success|failure}`.
  - A failure is logged once at ERROR with its SQLSTATE when there is one, and never propagates out of the scheduler thread.
  - The lock is always released.
- **Jobs:**
  - `idempotency-cleanup` (hourly, existing, moved into `ScheduledJob`);
  - `operation-cleanup` (daily, `0 30 3 * * *`): deletes finished (`SUCCEEDED`/`FAILED`) operations created more than 7 days ago. Unfinished ones stay;
  - `event-publication-resubmit` (every 5 minutes): resubmits incomplete publications older than 5 minutes through `IncompleteEventPublications` (covers a rejected task or a crash without restart);
  - `event-publication-cleanup` (daily, `0 45 3 * * *`): deletes completed publications older than 7 days through `CompletedEventPublications`.
  - Every cron and retention is configurable under `seed.jobs.*`.
- **`migrate` profile:** no scheduler (existing), no outstanding-publication resubmission, no listener work.
- **Sample export:**
  - `POST /api/v1/sample-records/export` (USER) starts operation `SAMPLE_EXPORT` and publishes `SampleExportRequested(operationId, requestedBy, correlationId)` in one transaction.
  - It answers 202 "Export started" with `Location: /api/v1/operations/{id}` and data `{operationId, status: QUEUED, version: 1, statusTopic}`.
  - `SampleExportListener` marks the operation `RUNNING`, builds the CSV of all active records in memory (RFC 4180 quoting, header `id,name,description,createdAt`, `createdAt` in ISO-8601 UTC), and completes with `result: {rowCount}`.
  - Every file is DEMO ONLY, and the removal steps still pass.

**Never:**
- STOMP or WebSocket, `PgStatusListener`, its readiness check, `seed_ws_sessions_active` (9b).
- Object storage or an `artifactId` (story 10).
- Status changes outside `OperationService`.
- External calls inside status transactions.
- Unbounded executors.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Export start | USER `POST /api/v1/sample-records/export`; no token; a token without `USER` | 202 "Export started", `Location` to the operation, data `{status: QUEUED, version: 1, statusTopic}`; 401; 403 | N/A |
| Export completes | 3 active and 1 deleted record, then poll `GET /operations/{id}` | eventually `SUCCEEDED`, `version 3`, `result {rowCount: 3}`, `correlationId` = the starting request's | N/A |
| Notify | listen on `operation_status` during an export | two payloads, RUNNING v2 then SUCCEEDED v3 (`start` does not notify), in version order, each valid §9 JSON under 8,000 bytes | N/A |
| Snapshot access | requester; ADMIN; another USER; unknown id | 200; 200; 403 `FORBIDDEN`; 404 `OPERATION_NOT_FOUND` | N/A |
| Transitions | `complete` on a `QUEUED` operation; `markRunning` with a stale version | 409 `INVALID_STATE_TRANSITION`; row unchanged, no notify | N/A |
| Listener retry | a probe listener failing twice, then succeeding; another always failing; another throwing a `SeedException` | the first ends `SUCCEEDED` after 3 attempts; the second ends `FAILED` after 4 attempts with `errorCode OPERATION_FAILED` (its push carries `errorCode`/`errorMessage`), and one ERROR line; the third ends `FAILED` after 1 attempt; all publications completed | ERROR once |
| Context propagation | a request with `X-Correlation-Id: abc` and `sub=u-1` triggers an async listener | listener log lines carry `correlationId abc`, `user.id u-1` and `operationId`; audit `updated_by` = `user:u-1` | N/A |
| Restart / resubmission | an incomplete publication present at startup (non-migrate), its operation already `RUNNING`; an incomplete publication older than 5 minutes while running; a resubmitted event whose operation is already `SUCCEEDED` | the listener finishes the work without a second `markRunning` and the operation ends `SUCCEEDED`; the resubmit job processes it; nothing changes and the publication completes | N/A |
| Job lock | the advisory lock for `job:x` is held by another connection | the job body does not run; DEBUG "skipped" | N/A |
| Job run | `ScheduledJob.run("x", body)` | body sees actor `system:job:x` and MDC `correlationId` matching `job:x:<uuid>`; `seed_job_duration_seconds{job="x",status="success"}` recorded; lock released | N/A |
| Job failure | the body throws a `DataAccessException` | one ERROR line with `SQLSTATE`; `status="failure"` metric; the scheduler thread survives; lock released | ERROR once |
| Cleanups | operations aged 8 days (one `SUCCEEDED`, one `RUNNING`) and 1 day; completed publications aged 8 days and 1 day, plus an incomplete 8-day-old one | the 8-day `SUCCEEDED` operation and the 8-day completed publication are deleted; the `RUNNING` operation, the 1-day rows and the incomplete publication remain | N/A |
| Migrate | `migrate` run with an outstanding publication present | exit 0; the publication is not processed | N/A |
| Metrics | after an export | Prometheus has `seed_operation_total{kind="SAMPLE_EXPORT",status="SUCCEEDED"}` and `seed_job_duration_seconds` after a job | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/idempotency/IdempotencyCleanup.java:41` -- `@Scheduled(cron = "${seed.idempotency.cleanup-cron…}")` opening `SystemActor.run`; move its body into `ScheduledJob`.
- `backend/src/main/java/com/company/seed/config/SchedulingConfig.java` -- `@EnableScheduling @Profile("!migrate")`; add `AsyncConfig` (the TaskDecorator) here in `config`.
- `backend/src/main/java/com/company/seed/common/actor/{SystemActor,CurrentActor}.java` -- `SystemActor.current()` is public; the scope sets MDC `user.id`.
- `backend/src/main/java/com/company/seed/common/logging/CorrelationIdFilter.java` -- MDC key `correlationId` (`ApiResponses.CORRELATION_ID_MDC_KEY`); add an `operationId` MDC key constant.
- `backend/src/main/java/com/company/seed/common/error/{ErrorCode,ConflictException,ForbiddenOperationException,ResourceNotFoundException}.java` -- `INVALID_STATE_TRANSITION` exists; add `OPERATION_FAILED` and `OPERATION_NOT_FOUND`.
- `backend/src/main/java/com/company/seed/common/error/web/Causes.java` -- `findCause(Throwable, Class)`, for SQLSTATE (`SQLException`).
- `backend/src/main/java/com/company/seed/feature/sample/{api/SampleRecordController,application/SampleRecordService}.java` -- add export; the README removal steps must still work.
- `backend/src/main/resources/application{,-migrate}.yml` -- Flyway `common` location; add `seed.jobs.*`, `spring.task.execution.*`, Modulith settings; `migrate` turns resubmission off.
- `backend/src/test/java/com/company/fixtures/{db/TestDatabase,security/TestJwt,web/LenientRestClient}` -- test helpers; Awaitility is available.

## Things this relies on

- `spring-modulith-starter-jdbc` 2.1.2 (`spring.modulith.events.jdbc.schema`, `…schema-initialization.enabled`, the v2 `event_publication` layout), `@ApplicationModuleListener`, `CompletedEventPublications`, `spring.modulith.events.republish-outstanding-events-on-restart` -- Spring Modulith 2.1.2 BOM
- `org.springframework.core.retry.RetryTemplate`, `@EnableAsync` -- Spring Framework 7
- `IncompleteEventPublications#resubmitIncompletePublicationsOlderThan` -- Spring Modulith 2.1.2
- api-standards.md §9 (push payload) and architecture-diagrams.md §4 (state machine) -- spec
- PgJDBC `PGConnection#getNotifications` (tests read notify payloads; the driver also goes on the test compile classpath) -- PostgreSQL JDBC 42.7
- Boot `TaskDecorator` auto-applied to `applicationTaskExecutor`, `spring.task.execution.pool.*` -- Spring Boot 4.1.2
- PostgreSQL `pg_notify`, `pg_try_advisory_lock`/`pg_advisory_unlock`, `hashtext`, `jsonb` -- PostgreSQL 16
- Micrometer `Timer`/`Counter` (Prometheus names `seed_job_duration_seconds`, `seed_operation_total`) -- Micrometer 1.17
- Jackson 3 `JsonMapper` (push payload, `result` jsonb) -- Boot BOM
- Awaitility, Testcontainers 2, Docker -- already in the build

## Tasks & Acceptance

**Execution:**
- [x] Build: `spring-modulith-starter-jdbc` in the catalog and build.
- [x] Migrations: `db/migration/common/V202611171000__common_create_event_publication.sql`, `V202611171005__common_create_operation.sql`.
- [x] `config/AsyncConfig.java` -- the `TaskDecorator` and pool settings.
- [x] `common/jobs/{ScheduledJob,JobsProperties}.java` + `IdempotencyCleanup` moved onto it; `common/operations/infrastructure/scheduling/OperationCleanupJob`; an event-publication cleanup job (in `common.jobs` or `config`).
- [x] `common/operations/{domain,application,infrastructure/persistence,api}/**` -- `Operation`, `OperationStatus`, `OperationRepository` + JPA implementation (conditional native updates, `pg_notify`), `OperationService`, `OperationListenerSupport` (scope, MDC, retry, fail), `OperationController`.
- [x] `common/error/ErrorCode.java` -- `OPERATION_FAILED`, `OPERATION_NOT_FOUND`.
- [x] `feature/sample/**` -- `SampleExportRequested`, export endpoint, `SampleExportListener` (DEMO ONLY); README removal steps updated if needed.
- [x] `application.yml`, `application-migrate.yml` -- jobs, executor, Modulith settings.
- [x] Tests:
  - `OperationPersistenceIT` (conditional updates, notify payloads via a raw LISTEN connection);
  - `OperationServiceTest`;
  - `OperationApiIT`;
  - `SampleExportIT` (end to end with Awaitility);
  - `OperationListenerSupportIT` (retry, then FAILED);
  - `AsyncContextIT`;
  - `ScheduledJobIT`;
  - `CleanupJobsIT`;
  - the migrate case in `MigrationIT`;
  - the metrics assertions.
  - Every matrix row is covered.
- [x] `deferred-work.md` -- nothing new is expected; 9b is already logged.
- [x] `README.md` -- jobs, operations, export, and the executor settings.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, with `api.yaml` regenerated for the two new endpoints and the module and architecture rules green.
- Given the README removal steps applied to a scratch copy, when the build runs, then it passes (recorded in Implementation Notes).

## Implementation Notes

- Planning: one critic pass (12 findings), all resolved by spec edits the user approved. The user's "apply your recommendation" included proceeding without a second pass (2026-11-17).

- Implementation: the implementation subagent stopped at the account's weekly usage limit after adding only the `spring-modulith-starter-jdbc` dependency. With the user's approval (option a, 2026-11-17), the main agent implemented the rest directly from this spec.
- Choices made while implementing:
  - **Operations persistence:** `JdbcOperationRepository` uses `JdbcClient`, not JPA. The conditional `UPDATE … RETURNING` and the `jsonb` result are plain SQL, and there is no entity for `ddl-auto=validate` to check.
  - **Status changes:** they are `REQUIRES_NEW`, so each commits (and notifies) at once. The listener's work also runs in a transaction per attempt.
  - **Retry backoff:** configurable through `seed.operations.retry-delay` (default `1s`; tests use 10 ms).
  - **ADMIN check:** `CurrentActor.isAdmin()` is new; `SecurityContextActor` implements it from the token's authorities. This lets `common` allow ADMIN without naming roles.
  - **Export response:** `OperationAcceptedResponse` is the 202 data.
  - **Readiness test:** `ReadinessIT$NeverMigrated` also turns off startup resubmission. Like `ddl-auto=validate`, the event registry fails startup when its table is missing (story 7 decision: fail fast).
  - **`IssuerIT` fixes (story 7 tests):** the stub issuer is now stopped after its class. It stayed bound to `127.0.0.1:<port>`, and a later Tomcat, which binds the wildcard address with address reuse, could get the same port, so `localhost` requests reached the stub; `CorrelationIdIT` failed intermittently with 400/405. The unreachable issuer is now port 1, not a released ephemeral port.
- snap-guard:
  - Four SLOP-ERR-04 findings were fixed by returning explicit outcomes (`ScheduledJob.Outcome`, the listener's retry outcome).
  - One SLOP-DUP-01 on `OperationListenerIT`'s import block is overridden: the six-line run of standard test imports (`Map`, `Test`, `ExtendWith`, `Autowired`, `CapturedOutput`, `OutputCaptureExtension`) is shared by every log-capturing IT, and Java imports cannot be extracted.
- Verify (main agent):
  - `./gradlew clean build` passes twice in a row with 684 tests in 74 suites and 0 failures; `api.yaml` is regenerated (`getOperation`, `startSampleRecordExport`).
  - The build also passes with `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `SEED_WRITES_PER_MINUTE` and `OIDC_AUDIENCE` exported.
  - The migrate test was mutation-checked: with resubmission turned on in `migrate` it fails, and it passes when turned off.
  - Removability: README steps 1–3 on a scratch copy, then `updateOpenApi` and `clean build`, pass.

- Review round 1 (main agent; the implementation subagent was unavailable): patches P1–P10 in the Review Triage Log. Further notes:
  - **Moments:** `spring.modulith.moments.enabled: false`. The unused moments auto-configuration carries `@EnableScheduling`, so `SchedulingConfig`'s `!migrate` had never kept the scheduler out of `migrate`, which the migrate run's log showed. The migrate test now asserts that the staleness monitor does not start there.
  - **Staleness:** `published` 5m, `processing` and `resubmitted` 15m. The resubmit job then finds stuck publications as `FAILED`. A work still running after 15 minutes may run a second time; the operation keeps the first outcome recorded.
  - **Listeners:** they use `propagation = NOT_SUPPORTED` (one pooled connection per thread at a time).
  - **What the Tasks list was built as:**
    - conditional updates and notify payloads → `OperationNotifyIT`;
    - `OperationServiceTest` (added in review);
    - listener retry and `FAILED` → `OperationListenerIT`;
    - async context → `AsyncConfigTest`, plus `CorrelationIdIT`'s async envelope (`correlationId` now asserted) and `SampleExportIT`;
    - persistence → `JdbcOperationRepository` (`JdbcClient`, see above).
  - **`deferred-work.md`:** the 9b entry was appended when the story was split; the earlier "Story 9" WebSocket entry predates the split.
  - **Verify after review:**
    - `./gradlew clean build` passes twice in a row: 695 tests in 75 suites, 0 failures; the second run has `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `SEED_WRITES_PER_MINUTE` and `OIDC_AUDIENCE` exported. `api.yaml` is regenerated (`status` enum).
    - The strengthened migrate test was mutation-checked again: with resubmission on in `migrate`, it fails.
    - Removability: on a fresh scratch copy (backend and infrastructure), README steps 1–3, then `updateOpenApi` and `clean build`, pass.
    - One earlier full run, between the patches, had 49 failures, all in `SampleExportIT` and `SampleRecordApiIT`: 404s, as if another listener answered on their port. Both suites passed alone and in the three full runs since. The cause was not found; the reports were not kept. The verification runs now keep them.
  - **snap-guard on the patched diff:** two SLOP-ERR-04 (explicit results: `Change`, `lockFailed`) and one SLOP-TEST-06 (the staleness test now asserts the version and the resubmission) were fixed. Only the SLOP-DUP-01 override above remains.

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 52 raw findings; the edge-case and verification-gap layers were resumed once after the 5-hour usage limit). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | blind + edge + security + verif-gap | An exception from `complete()`/`fail()` (a push of 8,000 bytes or more, or a lost race) leaves the listener, so the publication stays incomplete and the work reruns on every resubmission | high | `PgOperationStatusNotifier` throws `IllegalArgumentException` inside `complete()`; a lost conditional update throws `InvalidOperationTransitionException`; both were outside the retry block. | patch (P1) |
| R2 | blind + edge + security | The same work can run twice at once (restart or staleness resubmission while another instance works) | medium | `RUNNING` resumes by design (the frozen block accepts the state found: at-least-once delivery). The harm was the loser's exception; with P1 the loser stops at INFO and the operation keeps the winner's outcome. | patch (P1) |
| R3 | blind + edge | A null member in `result` breaks `Operation` (`Map.copyOf`): the completion rolls back, or `GET` returns 500 for a stored `jsonb` null | medium | `Map.copyOf` rejects null values; `HashMap` and the `jsonb` read both carry them. | patch (P2) |
| R4 | blind | The resubmit job cannot recover a rejected task or a crash without restart (frozen line 60) | high | Modulith 2.1.2 `JdbcEventPublicationRepositoryV2` resubmits only `WHERE (STATUS = 'FAILED' OR (STATUS IS NULL AND COMPLETION_DATE IS NULL))`; a rejected or lost task stays `PUBLISHED`/`PROCESSING`, and the staleness monitor that marks those failed was not configured. | patch (P3) |
| R5 | main agent (found while verifying P3) | Scheduling runs in `migrate`: Modulith moments' auto-configuration carries `@EnableScheduling` and is on by default, so `SchedulingConfig`'s `!migrate` never held; there the staleness monitor marked an outstanding publication failed | medium | The migrate run's log showed `StalenessMonitorConfiguration` and "Marking stale publications … as failed". The moments module is unused. | patch (P3) |
| R6 | security + edge + blind | `ScheduledJob` keeps its session advisory lock on the pooled connection when the body throws an `Error` or the unlock fails, so every instance skips the job from then on | high | The unlock was not in a `finally`; `attempt()` caught only `RuntimeException`; a failed unlock only logged. | patch (P4) |
| R7 | blind | A run that cannot get a connection or take its lock records no `seed_job_duration_seconds` sample, so a failure alert misses a database outage | medium | The sample was taken only around the body. | patch (P4) |
| R8 | slop + blind | `LockRequest` duplicates the outer `SQLException` handling; `Outcome` is read by no caller or test, and the skipped test does not check it | low | Direct deletion; tests now assert each outcome. | patch (P4) |
| R9 | edge | Each listener thread holds two pooled connections (the listener's `REQUIRES_NEW` plus each attempt's and status change's), so 8 executor threads need 16 of the 10 | medium | `@ApplicationModuleListener` defaults to `REQUIRES_NEW`; Hibernate takes the connection at begin. | patch (P5) |
| R10 | edge | A requester whose `sub` holds whitespace or controls makes every delivery fail in `SystemActor.run`, resubmitted forever | medium | `SecurityContextActor` accepts any non-blank `sub`; `SystemActor.requireValid` rejects it before any work. | patch (P6) |
| R11 | edge + blind | `fail()` cuts the message with `substring`, which can split a surrogate pair | low | Direct code-point-safe cut. | patch (P6) |
| R12 | blind | The notifier's error says "exceeds 8,000 bytes" but rejects 8,000 | low | Message text. | patch (P6) |
| R13 | slop | `insert(operation, actor)`: `actor` always equals `requestedBy`; `OperationMdc` is a holder class for one key, unlike the codebase's MDC constants | low | One caller; `CurrentActor.MDC_KEY` / `ApiResponses.CORRELATION_ID_MDC_KEY` pattern. | patch (P6) |
| R14 | security + edge | CSV/formula injection: user `name`/`description` starting with `= + - @`, tab or CR | medium | `field()` only quoted. Story 10 makes the file downloadable. | patch (P7) |
| R15 | security + edge | The export loads every record into memory (up to 8 at once) | medium | True, but the 9a CSV is discarded after counting; streaming belongs with story 10's upload to storage. | defer |
| R16 | blind + edge + verif-gap + slop | `findAllActive` was inserted between `restore`'s Javadoc and `restore` | low | The doc block was stranded. | patch (P8) |
| R17 | blind | The `ReadinessIT` comment was reflowed into a one-word line | low | Cosmetic. | patch (P8) |
| R18 | blind | The API advertises a WebSocket topic not served until 9b; `status` has no `enum` in the contract | low | `OperationController`/`OperationResponse` Javadoc; `String status`. | patch (P9) |
| R19 | blind | README jobs table mixes properties and environment variables; `JobsProperties` Javadoc wrong for `idempotency-cleanup`; `OperationCleanupJob` relies on `EventPublicationJobs` to register `JobsProperties` | low | Removing `EventPublicationJobs` would break the cleanup job's wiring. | patch (P9) |
| R20 | blind | Cron and retention defaults repeated in `application.yml`, `@Scheduled` placeholders and `@DefaultValue`; `common.operations` coupled to `common.jobs` | low | Same pattern as story 6c's `IdempotencyCleanup`; `application.yml` is authoritative. `operations` already depends on `jobs` for `ScheduledJob`. | rejected |
| R21 | verif-gap | `MigrationIT` checks only `completion_date`, which an async listener may not have written before the migrate run closes | medium | Pre-verified. | patch (P10) |
| R22 | verif-gap | The `TaskDecorator`'s wiring is untested at its consumers (the async success envelope's `correlationId`) | medium | Pre-verified; `CorrelationIdIT` deferred the check to story 9. | patch (P10) |
| R23 | verif-gap | `CleanupJobsIT` has no old `FAILED` (deleted) or old `QUEUED` (kept) operation | medium | Pre-verified. | patch (P10) |
| R24 | verif-gap | No REST read of a `FAILED` operation (`errorCode`, `errorMessage`, `kind`, `createdAt`) | medium | Pre-verified. | patch (P10) |
| R25 | blind + edge | No test of the stored `errorCode` on a permanent failure; no `OperationServiceTest` (access rule, truncation) as the Tasks list | medium | Tests missing. | patch (P10) |
| R26 | blind | A permanent `SeedException` ends as the generic `OPERATION_FAILED` instead of its own code | false | The frozen block (line 47) sets `OPERATION_FAILED`, "The operation failed" for every final failure. | rejected |
| R27 | blind | Redelivered work logs without `user.id` | false | `SystemActor.call` sets `user.id` from the scope (`SystemActor.java:44-46`), as the verification-gap layer also found. | rejected |
| R28 | blind | The story file is missing from the reviewed diff | false | Story specs are left out of review diffs on purpose; the edge-case layer received the spec as its claims file. | rejected |
| R29 | blind | The advisory lock key shares PostgreSQL's key space with Flyway's | false | `hashtext` returns an `int4`; Flyway's lock number starts at 77,431,708,279 plus an `int`, outside that range. | rejected |
| R30 | security | 403 for another user's operation but 404 for an unknown one reveals that an ID exists | low | The frozen block (lines 49, 84) chooses 403; UUIDv7 IDs carry 74 random bits, so they cannot be enumerated. | rejected |
| R31 | security | Queued work is not re-checked against the requester's current access | low | Stateless JWT with no user store; the request was authorised when accepted. | rejected |
| R32 | security | A forged `event_publication` row can run work as any actor | low | Needs write access as the app role, which can already write every table. | rejected |
| R33 | security | Any database role can `LISTEN` to status pushes | low | Only the app and owner roles connect; the payload holds no secrets. | rejected |
| R34 | security | Actor IDs are not bound to the token issuer | low | Story 7's `user:<sub>` format with one issuer; matters only with several issuers. | defer |
| R35 | edge | A job holding its lock connection past the 20 s leak threshold logs a leak warning | low | Each job's statement is bounded by the 15 s statement timeout; a warning only. | rejected |
| R36 | edge | One unbounded `DELETE` per cleanup could hit the statement timeout | low | Retention keeps the table small; the idempotency cleanup does the same. | rejected |
| R37 | edge | Cleanup by `created_at` can delete an operation that just finished after a week | low | The frozen block (line 59) deletes finished operations "created more than 7 days ago". | rejected |
| R38 | edge (claims) | Tasks name a JPA implementation and test classes that do not exist under those names; the `deferred-work.md` task says nothing new is expected | low | The test gap is R25; the actual names and the 9b entry (added at the split) are recorded in Implementation Notes. | rejected |
| R39 | slop | `JdbcClient` is a second data-access style next to the `EntityManager` native queries | low | Recorded choice (Implementation Notes); it joins the JPA transaction, as the operation inserted with the export request shows. | rejected |
| R40 | snap-guard | SLOP-DUP-01 on `OperationListenerIT`'s import block | low | Standard test imports cannot be extracted (Implementation Notes). | rejected |

Patches (applied by the main agent; the implementation subagent was unavailable):
- **P1:** `OperationListenerSupport` stops quietly when another delivery changed the status first. A result that cannot be recorded fails the operation (`OPERATION_FAILED`) instead of escaping.
- **P2:** `Operation` keeps null members.
- **P3:**
  - `spring.modulith.events.staleness` is set: `published` 5m; `processing` and `resubmitted` 15m.
  - `spring.modulith.moments.enabled: false`, so scheduling really is off in `migrate`.
  - README and Javadoc are updated.
- **P4:** in `ScheduledJob`:
  - the unlock is in a `finally`;
  - a failed unlock aborts the connection;
  - lock and connection failures count as `failure` in the metric;
  - `LockRequest` is gone.
- **P5:** listeners declare `propagation = NOT_SUPPORTED`. This is documented in `OperationListenerSupport` and the README.
- **P6:**
  - `start` rejects an actor no scope accepts;
  - `fail` cuts without splitting a character;
  - the notifier message is fixed;
  - `insert(operation)` takes no separate actor;
  - `OPERATION_ID_MDC_KEY` moves onto `OperationListenerSupport`.
- **P7:** a CSV field that a spreadsheet would read as a formula gets a leading `'`.
- **P8:** the `restore` Javadoc is restored and the `ReadinessIT` comment rewrapped.
- **P9:**
  - Javadoc says the topic arrives in 9b;
  - `status` is typed `OperationStatus` (`api.yaml` now has the enum);
  - the README table and `JobsProperties` Javadoc are fixed;
  - `OperationCleanupJob` registers `JobsProperties` itself.
- **P10:** tests added:
  - the migrate row stays `PUBLISHED`, never resubmitted, and the run starts no staleness monitor;
  - the async envelope carries `correlationId`;
  - `FAILED` and `QUEUED` cleanup rows;
  - a REST read of a `FAILED` operation;
  - the permanent failure's `errorCode`;
  - `OperationServiceTest`;
  - a result that is too large;
  - null members;
  - a lost race;
  - a stale publication marked failed and resubmitted;
  - the job outcomes, and the lock released after an `Error`.

## Design Notes

**Why the JDBC registry rather than `starter-jpa`** (stack.md names the JPA one): the JDBC registry takes `spring.modulith.events.jdbc.schema`, so `event_publication` lives in `common` as data-and-migrations.md §1 requires. The JPA registry maps an unqualified table, and `ddl-auto=validate` would look for it in `public`. The behaviour (transactional publish, resubmission) is the same.

**Why the listener returns normally after `FAILED`:** the operation records the failure for the user. A thrown exception would leave the publication incomplete, and it would be resubmitted on every restart, retrying a permanent failure forever.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `checkOpenApi` green after `updateOpenApi`.

## Tech Spec Critic Findings

- [SOLVED] [high] check-1 | spec line 81 | A restart mid-operation usually leaves the operation `RUNNING`, but `markRunning` from `RUNNING` is illegal; the Restart resubmission row does not say the operation's state or what the user ends up seeing (finished export vs `FAILED` after three 409s).
- [SOLVED] [medium] check-5 | spec line 54 | "deletes operations created more than 7 days ago" can mean every operation or only finished ones, so an old `QUEUED`/`RUNNING` operation with a pending publication might be deleted, leaving a later resubmission nothing to update.
- [SOLVED] [medium] check-5 | spec line 36 | The push JSON is §9's payload, which has no `errorCode`/`errorMessage`, yet `error_message` is "cut to fit", so it is unclear whether a FAILED push says why.
- [SOLVED] [medium] check-1 | spec line 41 | errors-and-logging.md §2 says "retried up to 3 times" (4 attempts) and retries only retryable failures; the spec allows 3 attempts and retries every failure, including permanent ones such as `INVALID_STATE_TRANSITION`.
- [SOLVED] [medium] check-3 | spec line 74 | The export endpoint has no row for rejected callers (no token → 401, no USER → 403).
- [SOLVED] [medium] check-3 | spec line 31 | No row covers a full executor queue: what the user sees when the export's listener task is rejected (e.g. the operation stays `QUEUED` until a restart).
- [SOLVED] [medium] check-6 | spec line 103 | Async `@ApplicationModuleListener` needs `@EnableAsync`, which the codebase lacks and `## Things this relies on` does not list.
- [SOLVED] [low] check-6 | spec line 103 | api-standards.md §9 and architecture-diagrams.md §4 are relied on but are neither in `## Things this relies on` nor in `context`.
- [SOLVED] [low] check-6 | spec line 125 | Reading notify payloads needs the PgJDBC notification API (`PGConnection.getNotifications`); the driver is only `runtimeOnly` and is not listed.
- [SOLVED] [low] check-2 | spec line 59 | The 202 response's envelope `message` is not stated (api-standards.md shows "Export started").
- [SOLVED] [low] check-5 | spec line 60 | The CSV escaping rule and the `createdAt` format are not named, so names or descriptions with commas, quotes or newlines could be written two ways.
- [SOLVED] [low] check-5 | spec line 47 | `job:<name>:<uuidv7>` is 66 characters for `event-publication-cleanup`, beyond the 64-character correlation-ID format in api-standards.md.
