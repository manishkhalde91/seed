---
title: 'Story 9b — Live operation status over WebSocket'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_commit: 'd943a8753bdbbc9216ce460672a5f9e60cef9c6c'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/realtime-and-storage.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/security.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Operations (9a) change status, and every change is `pg_notify`'d, but nothing pushes it to clients: the REST snapshot is the only status channel. CAP-10 needs live status. The deferred story-9 items wait for the STOMP endpoint: ERROR frames, the correlation ID from CONNECT, the LISTEN readiness check and `seed_ws_sessions_active`.

**Approach:** The 9b slice (2026-11-17, answer "S"):
- a STOMP endpoint `/ws` on the simple in-memory broker, authenticated on CONNECT and authorised per subscription (api-standards.md §9, realtime-and-storage.md §1.2–§1.3, security.md §3–§4);
- `PgStatusListener` on every instance, turning `operation_status` notifications into pushes on `/topic/operations/{id}`, with resync after a reconnect.

## Boundaries & Constraints

**Always:**
- **Endpoint:**
  - `spring-boot-starter-websocket`; plain WebSocket (no SockJS) at `/ws`; STOMP 1.2; application prefix `/app`; simple broker on `/topic`; heart-beat `10000,10000`.
  - Limits: inbound messages up to 16 KB, `sendTimeLimit` 15 s, `sendBufferSizeLimit` 512 KB.
  - The handshake checks `Origin` against `CORS_ALLOWED_ORIGINS` (`seed.security.cors-allowed-origins`). A request with no `Origin` header (a non-browser client) passes.
  - The HTTP security chain lets `/ws` through without a token: the token goes in the CONNECT frame, never the URL.
- **CONNECT:**
  - `Authorization: Bearer <jwt>` is decoded with the application's `JwtDecoder` and role converter, so the rules are the same as REST (issuer, audience, `exp`/`nbf` with skew, `sub`, roles).
  - A missing or invalid token gets ERROR `unauthorized` and the connection closes.
  - Any frame other than CONNECT on an unauthenticated session gets ERROR `unauthorized`.
- **Subscriptions:**
  - `SUBSCRIBE /topic/operations/{id}` and `/app/operations/{id}` (the snapshot) need read access to the operation, by the same rule as `GET /api/v1/operations/{id}`: its requester or `ADMIN`.
    - unknown id, or an id that is not a UUID → ERROR `not-found`;
    - not allowed → ERROR `forbidden-destination`.
  - `/topic/system/resync` is open to any authenticated session. Any other destination → ERROR `forbidden-destination`.
  - A session's 51st active subscription → ERROR `too-many-subscriptions`. `UNSUBSCRIBE` frees a slot.
  - `SEND` to any destination → ERROR `forbidden-destination`: the channel is push-only.
  - Every ERROR frame closes the connection. Its `message` header is exactly the code, with no details.
- **Snapshot:**
  - `@SubscribeMapping("/operations/{id}")` replies once with the push payload of api-standards.md §9 (not enveloped), built by the same code as the `pg_notify` payload.
  - A failure inside the handler goes through a `@MessageExceptionHandler`. It maps `OperationNotFoundException` → `not-found`, `ForbiddenOperationException` → `forbidden-destination`, and anything else → `internal-error` (logged once at ERROR), sends that ERROR frame and closes.
- **Context:**
  - CONNECT's `X-Correlation-Id`, if it matches the REST format, becomes the session's correlation ID; otherwise a new UUIDv7.
  - While a client frame is handled, MDC has the session's `correlationId` and `user.id`, and the security context is the session's user (so `CurrentActor` and method security work).
  - Pushes carry the `correlationId` of the change that caused them, as the payload already does.
- **Token expiry:** every `seed.realtime.token-check-interval` (default `30s`), a session whose JWT `exp` has passed gets ERROR `token-expired` and is closed.
- **Shutdown:** before the web server stops, every open session is closed with status 1012 (service restart).
- **`PgStatusListener`** (every web instance, never in `migrate`):
  - holds one dedicated connection outside the Hikari pool (the datasource URL, user and password) and runs `LISTEN operation_status` on a thread of its own;
  - sends each notification's payload unchanged to `/topic/operations/{operationId}`. A payload that cannot be read is logged at WARN and skipped;
  - when the connection drops, it reconnects with backoff from 1 s doubling to 30 s. After a successful reconnect it publishes `{"reason":"listener-reconnected"}` on `/topic/system/resync`;
  - health `operationStatusListener` is UP while listening and DOWN while disconnected, and it is part of the readiness group.
- **Metric:** gauge `seed_ws_sessions_active` (connected STOMP sessions).
- **Docs:**
  - README: the realtime contract, the `seed.realtime.*` settings, and that the load balancer must forward `Upgrade`/`Connection` with an idle timeout of at least 60 s.
  - The 9a Javadoc and README notes that say "story 9b" are updated.

**Never:**
- SockJS, a broker relay, user queues (`/user/**`), or `@MessageMapping` handlers for SEND.
- The token in the URL or a query parameter; a pooled connection for LISTEN.
- `@EnableWebSocketSecurity`: its CSRF requirement on CONNECT does not fit bearer tokens.
- The frontend client (a frontend story); object storage (story 10).
- Changes to the operation model, its REST API or the push payload.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Connect | CONNECT with a valid USER token; no token; a malformed token; a token for another audience | CONNECTED; ERROR `unauthorized` ×3, closed | N/A |
| Origin | handshake with `Origin` allowed; not allowed; absent | upgraded; refused (403, no upgrade); upgraded | N/A |
| Live push | the requester subscribes to `/topic/operations/{id}`, then the operation goes RUNNING and SUCCEEDED | two pushes, v2 then v3, each the §9 payload with the starting request's `correlationId` | N/A |
| Snapshot | `SUBSCRIBE /app/operations/{id}` by the requester; by ADMIN | one message, the current §9 payload | N/A |
| Subscription denied | another USER subscribes to the topic or the snapshot; unknown id; `not-a-uuid`; `/topic/other` | ERROR `forbidden-destination`; `not-found`; `not-found`; `forbidden-destination`; closed | N/A |
| Limits | 50 subscriptions then a 51st; unsubscribe one and subscribe again; a 17 KB frame | 51st → ERROR `too-many-subscriptions`; the re-subscribe succeeds; the oversized frame closes the session | N/A |
| Push-only | `SEND /app/anything` | ERROR `forbidden-destination`, closed | N/A |
| Token expiry | CONNECT with a token expiring in 2 s, check interval 200 ms | ERROR `token-expired`, closed within a few seconds | N/A |
| Fan-out | two application contexts on one database; the client is connected to B; the operation changes through A | the client receives the push from B | N/A |
| Listener reconnect | the listener's backend is terminated (`pg_terminate_backend`) | health DOWN, then UP after the reconnect; `{"reason":"listener-reconnected"}` on `/topic/system/resync`; later pushes still arrive | WARN on the drop |
| Correlation | CONNECT with `X-Correlation-Id: ws-abc`; with an invalid one | handling log lines carry `correlationId ws-abc`; a new UUIDv7 | N/A |
| Shutdown | the context closes with a session open | the client sees close status 1012 | N/A |
| Metric | two sessions connected, then one disconnects | `seed_ws_sessions_active` 2, then 1 | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/security/SecurityConfig.java:160-174` -- request rules; add `/ws` (and `/ws/**`) `permitAll` for the handshake. The `jwtDecoder` bean is at :298 and `jwtValidator` at :236; the `JwtAuthenticationConverter` and `JwtRoleConverter` setup at :143-145 is reused for CONNECT (extract a small factory instead of copying it).
- `backend/src/main/java/com/company/seed/security/package-info.java` -- `security` may depend only on `common`. The STOMP auth interceptor and its `WebSocketMessageBrokerConfigurer` (`configureClientInboundChannel`) go in `security/ws/` (backend-architecture.md §4.4). The subscription check calls `common.operations.application.OperationService#getForCurrentActor` (403 → `ForbiddenOperationException`, unknown → `OperationNotFoundException`).
- `backend/src/main/java/com/company/seed/common/operations/infrastructure/` -- add `ws/` (endpoint and broker config, session registry: count gauge, expiry check, 1012 on shutdown, close-with-ERROR; the ERROR-frame error handler) and `notify/PgStatusListener` plus its health indicator. `common` must not import `security`: share the session-attribute key for the token expiry as a constant in `common`.
- `backend/src/main/java/com/company/seed/common/operations/infrastructure/notify/PgOperationStatusNotifier.java:47` -- `payload(Operation)` builds the §9 map. Move it into `common/operations/application/` so the snapshot handler and the notifier share it; the notifier keeps its 8,000-byte check.
- `backend/src/main/java/com/company/seed/common/operations/api/` -- add the `@Controller` with `@SubscribeMapping` and `@MessageExceptionHandler`. ArchUnit's `@PreAuthorize` rule covers request-mapped methods only (`SeedArchitectureRules.java:225-240`); authorisation is the subscription check.
- `backend/src/main/java/com/company/seed/common/logging/CorrelationIdFilter.java:41,50` -- `HEADER`, `TOKEN` pattern; reuse for CONNECT.
- `backend/src/main/java/com/company/seed/common/actor/CurrentActor.java:26` -- `MDC_KEY` (`user.id`); `common/api/ApiResponses.CORRELATION_ID_MDC_KEY`.
- `backend/src/main/java/com/company/seed/security/SeedSecurityProperties.java:28` -- `corsAllowedOrigins` (`seed.security.cors-allowed-origins`, `application.yml:156`).
- `backend/src/main/resources/application.yml:187-191` -- readiness group `readinessState,db,schemaVersion`; add `operationStatusListener`. Add `seed.realtime.token-check-interval: 30s`.
- `backend/src/main/java/com/company/seed/common/operations/{api/OperationController,api/OperationResponse}.java` and README §5c -- the "story 9b" notes become present tense.
- Tests: `fixtures/operations/OperationsTestSupport` (database `seed_operations`, probe operations, `startProbeAs`, `awaitStatus`), `fixtures/security/{TestSecurity,TestJwt}` (`TestJwt.builder().expiresAt(...)`, `.subject(...)`, `.roles(...)`), `fixtures/db/TestDatabase`; Awaitility. A second context for the fan-out test can use `SpringApplicationBuilder` on the same database with `TestSecurity`.

## Things this relies on

- `spring-boot-starter-websocket` 4.1.2 (STOMP over the simple broker, `WebSocketMessageBrokerConfigurer`, `SimpMessagingTemplate`, `WebSocketStompClient` + `StandardWebSocketClient` for tests) -- Maven Central / Spring Boot BOM
- `spring-security-messaging` 7.1.2 (`SecurityContextChannelInterceptor`, so handlers see the session's user; no `@EnableWebSocketSecurity`) -- Maven Central / Spring Security BOM
- `StompSubProtocolErrorHandler`, `@SubscribeMapping`, `@MessageExceptionHandler`, `WebSocketTransportRegistration` limits, `StompEndpointRegistry#setAllowedOrigins` -- Spring Framework 7
- PgJDBC `PGConnection#getNotifications(int timeoutMillis)`, `LISTEN`, `pg_terminate_backend` -- PostgreSQL JDBC 42.7 / PostgreSQL 16
- Jackson 3 message conversion for STOMP payloads (Boot-managed `JsonMapper`, stack.md §pitfalls 1) -- Spring Boot 4.1.2
- Micrometer `Gauge` (`seed_ws_sessions_active`) -- Micrometer 1.17
- `JwtDecoder`, `JwtAuthenticationConverter`, `JwtRoleConverter`, `OperationService#getForCurrentActor`, `CorrelationIdFilter.TOKEN` -- this repo

## Tasks & Acceptance

**Execution:**
- [x] `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts` -- add `spring-boot-starter-websocket` and `spring-security-messaging`.
- [x] `common/operations/application/` -- the shared §9 payload builder; `PgOperationStatusNotifier` uses it.
- [x] `common/operations/infrastructure/ws/**` -- endpoint/broker config with limits, heart-beat scheduler and allowed origins; session registry (gauge, expiry check, 1012 on shutdown, close with ERROR); ERROR-frame error handler; inbound MDC/correlation handling.
- [x] `common/operations/api/` -- the snapshot `@SubscribeMapping` and `@MessageExceptionHandler`.
- [x] `common/operations/infrastructure/notify/` -- `PgStatusListener` (lifecycle, reconnect/backoff, resync) and its health indicator.
- [x] `security/ws/**` + `SecurityConfig` -- CONNECT authentication, the subscription/SEND rules and the 50-subscription limit, the security context for handlers, the `/ws` handshake rule.
- [x] `application.yml` -- readiness group, `seed.realtime.token-check-interval`.
- [x] Tests: `OperationStatusStompIT` (one row per matrix scenario except fan-out and reconnect), `OperationStatusFanOutStompIT` (two contexts), `PgStatusListenerIT` (reconnect, resync, health), and a unit test for the error-code mapping.
- [x] `README.md`, 9a Javadoc -- the realtime contract, settings and load-balancer note.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes with the module and architecture rules green and `api.yaml` unchanged.
- Given the README removal steps applied to a scratch copy, when the build runs, then it passes.

## Implementation Notes

- Implemented by the implementation subagent (Opus) from this spec. Verified by the main agent: `./gradlew clean build` passes (723 tests in 79 suites, 0 failures, 0 skipped); `api.yaml` unchanged. The new suites `OperationStatusStompIT` (20), `OperationStatusFanOutStompIT` (1), `PgStatusListenerIT` (1) and `StompErrorsTest` (6) ran, and every matrix row has a passing test.
- Changes outside the Code Map, needed because the broker changes Boot's defaults:
  - `spring.task.execution.mode: force`. Boot no longer creates `applicationTaskExecutor` once the broker defines executors, so async requests and operation listeners would lose their context (`CorrelationIdIT` failed).
  - The STOMP inbound and outbound channels get their own pools, so operation work cannot hold back frames or pushes.
  - `SchedulingConfig` defines `taskScheduler` (Boot's builder). Otherwise jobs would run on the broker's heart-beat scheduler.
  - The `operationStatusListener` health contributor exists in every context. It reports UNKNOWN without a web server, so the readiness group stays valid in `migrate` and non-web tests.
  - The PostgreSQL driver is now compile scope (`PGConnection` notifications).
  - `JwtAuthenticationConverter` is a shared bean, so REST and CONNECT accept the same callers.
- Ambiguities the critic raised were left open at approval; the implementation resolved them as follows:
  - only `/topic/**` subscriptions count toward the 50; an `/app` snapshot holds no slot;
  - same-origin handshakes are accepted besides the listed origins (Spring's default);
  - token expiry uses the token's `exp` without clock skew;
  - an oversized frame closes the connection with 1009 and no ERROR frame;
  - `internal-error` is a sixth ERROR code, documented in the README.
- Implementer's risks: a status change between the topic subscription and the snapshot can be missed until the next push (the inbound channel is not ordered, so ERROR frames stay possible); Spring logs a refused `SEND` at ERROR; the stopped-database readiness case expects the listener to notice the drop first.
- snap-guard (implementer): 0 findings on the diff; opengrep, lizard and scc are not installed.

- Review round 1: the implementation subagent applied P1–P9 (Review Triage Log). Further notes:
  - **Ordered receive:** with `setPreserveReceiveOrder(true)`, Spring's ordered decorator only logs a refused send. The security interceptor therefore sends each refusal's ERROR frame itself (`StompSessionRegistry.closeWithError`) and drops the frame. An unexpected failure there is logged once at ERROR and refused with `internal-error`.
  - **Second CONNECT:** Spring itself rejects it ("Session already exists") before the interceptor, so `StompErrorHandler` maps a failed CONNECT to `unauthorized`; the interceptor checks for an existing user as a backstop.
  - **Expired tokens:** a CONNECT whose token's `exp` has passed (within the decoder's skew) and any later frame of an expired session get `token-expired`.
  - **New tests:** `TaskExecutorsIT` (executor and scheduler isolation), `OperationStatusSnapshotFailureIT` (handler failure → `not-found`/`internal-error`, its own context because of `@MockitoSpyBean`), an unreadable-notification case in `PgStatusListenerIT`, and ADMIN topic, expired-CONNECT, second-CONNECT and expiry-upper-bound cases in `OperationStatusStompIT`. The ArchUnit `@PreAuthorize` rule now covers `@MessageMapping`/`@SubscribeMapping`, with a violation fixture.
- Verify after review (main agent):
  - `./gradlew clean build` passes twice in a row: 732 tests in 81 suites, 0 failures, 0 skipped; the second run has `SPRING_PROFILES_ACTIVE`, `SEED_API_DOCS_ENABLED`, `SEED_WRITES_PER_MINUTE` and `OIDC_AUDIENCE` exported. `api.yaml` is unchanged. Reports were kept for both runs.
  - snap-guard on the final diff: 0 findings (38 files). Doubt-phrase scan: clean.
  - Removability: on a fresh scratch copy (backend and infrastructure), README steps 1–3, then `updateOpenApi` and `clean build`, pass.

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 53 raw findings; the edge-case and verification-gap layers were resumed once after the 5-hour usage limit). Duplicates across layers share one row. The implementation subagent applied the patches.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | blind + edge | Inbound frames of a session are handled concurrently: a snapshot can be built before the topic subscription is registered, so a change between them (possibly the terminal one) is lost; SUBSCRIBE/UNSUBSCRIBE can reorder in the broker | high | `setPreserveReceiveOrder` is off (the implementer's own risk note); Spring 7.0.10's `OrderedMessageChannelDecorator` only logs a failed send, which is why throwing refusals and ordering did not mix. | patch (P1: ordered receive; refusals send their ERROR frame through the session registry and drop the frame) |
| R2 | blind + edge | A second CONNECT re-authenticates the session and swaps its user while old subscriptions stay | medium | The interceptor runs before the broker, which only logs "Ignoring CONNECT". | patch (P2) |
| R3 | blind + security | CONNECT accepts a token up to 30 s past `exp` (validator skew), which the sweep then closes at once (connect/close loop); between `exp` and the sweep a session can still subscribe | medium | The sweep uses `exp` without skew; frames do not check it. | patch (P2) |
| R4 | blind | One push that fails to send drops a healthy LISTEN connection: readiness DOWN and a resync to every client | medium | `pump` catches `RuntimeException` from `forward()` as a drop. | patch (P3) |
| R5 | edge | A `RuntimeException` in the reconnect branch (`connect`/`publishResync`) ends the listener thread for good | medium | Only `InterruptedException` is caught around the loop. | patch (P3) |
| R6 | edge | `start()` after a `stop()` whose join timed out starts a second listener thread | low | `thread` is replaced unconditionally. | patch (P3) |
| R7 | security | The WARN for an unreadable notification logs up to 200 characters of the payload | low | `LogSafe.clean(payload)`. | patch (P3) |
| R8 | slop | Hand-rolled capped backoff where spring-core's `ExponentialBackOff` fits | low | Direct reuse. | patch (P3) |
| R9 | edge | One failing session aborts the expiry and 1012 loops; sessions opened after `ContextClosedEvent` miss the 1012 close | low | No per-session catch; no closing flag. | patch (P4) |
| R10 | edge | `X-Correlation-Id` has no lowercase fallback while `Authorization` has one | low | Inconsistent header lookup. | patch (P4) |
| R11 | security | The `@SubscribeMapping` handler has no `@PreAuthorize`, and the ArchUnit rule covers only request-mapped methods | medium | `SeedArchitectureRules.java:225-240`; a future `/app` handler would rely on the interceptor's allowlist alone. | patch (P5) |
| R12 | security | The handshake matcher `/ws/**` is wider than the one endpoint | low | Only `/ws` is registered. | patch (P5) |
| R13 | blind | One scheduler thread runs jobs, the staleness check and the token sweep, so a long job delays expiry | medium | Boot's default `spring.task.scheduling.pool.size` is 1. | patch (P6) |
| R14 | blind | `seed.realtime.token-check-interval` breaks the `${SEED_…:default}` convention | low | Sibling settings use placeholders. | patch (P6) |
| R15 | slop | `StompErrors` re-implements `Causes.findCause`; `isUnexpected` is test-only; `SecondInstance` has an unused varargs | low | Direct reuse and deletion. | patch (P7) |
| R16 | verif-gap | Executor and scheduler isolation (`mode: force`, `taskScheduler`, channel pools) is untested | medium | Pre-verified. | patch (P8) |
| R17 | verif-gap + blind | The snapshot `@MessageExceptionHandler` path never runs in a test | medium | Pre-verified. | patch (P8) |
| R18 | verif-gap + blind | Skipping an unreadable notification is untested | low | Verified; filed as `defer`, but the test is cheap next to P3. | patch (P8) |
| R19 | blind | No test of ADMIN on a live topic; the expiry test has no upper bound | low | Test gaps. | patch (P8) |
| R20 | blind + edge | README: heart-beats only flow when the client asks for them; hard-coded `:8080`; an over-long line; the oversized-frame outcome (a frame of 16,385-16,640 bytes gets ERROR, not 1009) | low | Spring raises the container limit above `MESSAGE_SIZE_LIMIT`; the server beats only on request. | patch (P9) |
| R21 | blind | The LISTEN connection is outside the pool and undocumented (counts against `max_connections`, needs session-mode pooling); it does not carry Hikari's driver properties | low | Documentation; the URL carries `sslmode` and `statement_timeout`, and pgjdbc's connect timeout is 10 s. | patch (P9, docs) |
| R22 | blind | No advice against a resync stampede after a failover | low | Documentation. | patch (P9) |
| R23 | blind + security | No cap on sessions per user or address, no handshake/CONNECT rate limit, unbounded channel queues, unlimited snapshot subscriptions | medium | Real; the REST write limiter has no WebSocket counterpart. New limits are new behaviour beyond this story's intent. | defer |
| R24 | blind + slop | Allowed origins bound a second time, without `SeedSecurityProperties` validation | low | `SeedSecurityProperties` validates the same property at startup in every web context (wildcards fail startup); `common` cannot import `security`. | rejected |
| R25 | blind | `api`→`infrastructure` and `security`→`common.operations.infrastructure` dependencies; the driver now on the compile classpath | low | Modulith and ArchUnit pass; `common` is an open module; only `notify` uses the driver API. | rejected |
| R26 | blind + security | Each snapshot reads the operation twice | low | Intentional: the handler reads the current state when it answers. | rejected |
| R27 | security | `pg_notify` payloads are forwarded unverified, so any database role can fake a push | low | Needs a database role, which can already write `common.operation`; same decision as story 9a R32/R33. | rejected |
| R28 | security | `not-found` vs `forbidden-destination` reveals that an operation exists | low | Mirrors REST 404/403 (story 9a frozen decision, R30). | rejected |
| R29 | security | A role revoked after `SUBSCRIBE` still receives pushes until the token expires | low | Stateless JWT, as for REST; P2 closes the expired-token window. | rejected |
| R30 | slop | `Connected`/`Parsed` records carry a failure nobody reads | low | snap-guard SLOP-ERR-04 requires an explicit failure result instead of a default value. | rejected |
| R31 | slop | The WebSocket config's Javadoc narrates the settings | low | It documents the contract with spec references. | rejected |

## Design Notes

**Why our own interceptor rather than `@EnableWebSocketSecurity`:** Spring Security 7's messaging configuration requires a CSRF token on CONNECT and cannot simply turn that off. A browser using bearer tokens has no CSRF exposure, so one `ChannelInterceptor` in `security/ws` decodes the token on CONNECT and checks SUBSCRIBE/SEND. `spring-security-messaging` supplies only the `SecurityContextChannelInterceptor`.

**Why ERROR frames come from the interceptor:** an exception thrown inside a `@SubscribeMapping` never reaches the client as a frame, so access is checked before dispatch. The `@MessageExceptionHandler` covers failures while the snapshot is built, and closes the session itself.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `checkOpenApi` green with no `api.yaml` change.

## Tech Spec Critic Findings

- [medium] check-1 | spec line 49 | The `@MessageExceptionHandler` sends a new ERROR code, `internal-error`. The §9 contract (api-standards.md) lists only `unauthorized`, `forbidden-destination`, `token-expired`, `too-many-subscriptions` and `not-found`, and the spec neither names nor justifies the addition, so clients built to the contract will get a code they do not know.
- [medium] check-5 | spec line 33 | "checks `Origin` against `CORS_ALLOWED_ORIGINS`" has two readings, and that list is empty by default. Reading one: a same-origin browser (frontend and API behind one proxy, where security.md §4 turns CORS off) is refused because its origin is not on the list. Reading two: same-origin is always allowed. The matrix (line 78) covers neither case.
- [medium] check-5 | spec line 44 | "A session's 51st active subscription" does not say what counts toward the 50. Reading one: every SUBSCRIBE counts, including one-off `/app/operations/{id}` snapshots and `/topic/system/resync`, so a client following topic plus snapshot hits the limit at about 25 operations. Reading two: only `/topic/operations/*` subscriptions count.
- [medium] check-5 | spec line 54 | "a session whose JWT `exp` has passed" does not say whether the 30 s clock skew that CONNECT accepts (line 36) applies. If it does not, a token CONNECT accepted just after `exp` is closed at the next check. If it does, the matrix row at line 84 (closed "within a few seconds" of a 2 s token) cannot hold.
- [medium] check-6 | spec line 107 | `## Things this relies on` leaves out settings and repo pieces the plan depends on: `seed.realtime.token-check-interval`, `seed.security.cors-allowed-origins`/`CORS_ALLOWED_ORIGINS`, the datasource URL/user/password used for the dedicated LISTEN connection, the readiness health group, Actuator `HealthIndicator`, the repo's UUIDv7 `IdGenerator`, `CurrentActor`/`CorrelationIdFilter.HEADER`, `OperationNotFoundException`/`ForbiddenOperationException`, and Awaitility/`TestJwt`.
- [low] check-2 | spec line 46 | An ERROR frame carries only its code. For `internal-error`, the client therefore never gets the correlation ID that errors-and-logging.md tells users to quote ("Unexpected error. Quote the correlation ID."), so nobody can trace the failure from what the client sees.
- [low] check-3 | spec line 38 | The rule that any frame other than CONNECT on an unauthenticated session gets ERROR `unauthorized` has no row in the I/O & Edge-Case Matrix (e.g. SUBSCRIBE or SEND before CONNECT).
- [low] check-3 | spec line 82 | For the 17 KB frame, the matrix says only "closes the session". It does not say what the client observes: an ERROR frame and which code, or a WebSocket close status.
- [low] check-1 | spec line 52 | The handling context puts only `correlationId` and `user.id` in MDC. errors-and-logging.md §4 also requires `operationId` on log lines when it is known, which applies to snapshot and subscription handling for `/operations/{id}`.
