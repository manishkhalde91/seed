# Monorepo seed — Spring Boot + Angular

A reusable starting point with a Spring Boot backend and an Angular frontend. A project copies it,
removes the sample feature ([The sample module](#the-sample-module)) and adds only its own
business modules. The full
contract is in [`_snap-output/specs/spec-monorepo-seed/SPEC.md`](_snap-output/specs/spec-monorepo-seed/SPEC.md);
decisions are in [`docs/adr/`](docs/adr/README.md).

| Directory | What it is | Builds with |
|---|---|---|
| `backend/` | Spring Boot 4.1 / Java 21 application, Spring Modulith module boundaries | `./gradlew clean build` |
| `frontend/` | Angular 21 workspace (standalone, zoneless, Vitest) | `npm ci && npm run build` |
| `infrastructure/` | Local developer services only (PostgreSQL, mock OIDC provider) | `docker compose` |
| `docs/adr/` | Architecture decision records | — |
| `docs/validation-rules.md` | Backend ↔ frontend validation parity, per request field | — |

Backend and frontend build and test independently; there is no root build orchestrator.

**Implemented so far:** repository skeleton, local infrastructure, the module-boundary checks
(Spring Modulith verification and ArchUnit rules run in every `./gradlew build`), the response
envelope and error handling, logging and health, the persistence foundation (PostgreSQL, Flyway,
auditing, soft delete, IDs), security (OIDC resource server, roles, `GET /api/v1/me`), operations
and housekeeping jobs (`GET /api/v1/operations/{id}`), the live operation status over WebSocket
(STOMP at `/ws`) and the sample feature's backend (DEMO ONLY), including its export. Object
storage and the Angular shell arrive in later stories.
CI (GitHub Actions), Sonar analysis and Dependabot arrive in a later story.

## First-time setup

This is the only setup path. Run every command from the repository root unless it says otherwise.

### 1. Prerequisites

- **Docker** with Compose v2, **or Podman** with `podman compose`. Every infrastructure command
  below is written as `docker compose …`; `podman compose` takes exactly the same arguments.
- **Docker** (or Podman) must also be running for the backend build: every `@SpringBootTest`
  starts PostgreSQL through Testcontainers (no in-memory database is used).
- **JDK 21** (Temurin recommended). With SDKMAN, `sdk env install` uses `.sdkmanrc`. No Gradle
  install is needed — the backend ships the Gradle wrapper (`backend/gradlew`), which downloads
  the pinned Gradle version and checks its SHA-256 checksum.
  `./gradlew` needs some Java 17+ on `JAVA_HOME` or `PATH` to start. The build itself (Gradle
  daemon, compile, tests) then runs on JDK 21, which Gradle finds through `JAVA_HOME` or by
  auto-detecting an installed JDK (SDKMAN, the macOS `/Library/Java/JavaVirtualMachines` folder,
  common Linux and Windows locations); it never downloads a JDK. Check what it sees with
  `(cd backend && ./gradlew -q javaToolchains)`. Homebrew's keg-only `openjdk@21` is not
  auto-detected: point `JAVA_HOME` at it, or add
  `org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21` to `~/.gradle/gradle.properties`.
  Without JDK 21 the build stops with a "Cannot find a Java installation … matching:
  {languageVersion=21 …}" error.
- **Node 24** (any 24.x release) with npm. With nvm, `nvm use` reads `.nvmrc`. `npm ci` refuses
  other Node majors.

### 2. Create your `.env`

```sh
cp .env.example .env
```

Replace every `replace-me-…` value with a local password of your choice. `.env` is git-ignored;
never commit it. `.env.example` holds placeholders only.

Compose interpolates `$` in `.env` values, so write a literal `$` as `$$` (for example
`DB_PASSWORD=pa$$word` sets the password `pa$word`).

### 3. Start the local infrastructure

```sh
docker compose --env-file .env -f infrastructure/compose.yaml up -d
docker compose --env-file .env -f infrastructure/compose.yaml ps
```

Wait until both services report `healthy`:

| Service | Host address | What it provides |
|---|---|---|
| `postgres` | `127.0.0.1:${POSTGRES_PORT}` (default 5432) | PostgreSQL 16, database `seed_db`, roles `seed_owner` (DDL) and `seed_app` (DML only) |
| `oidc` | `127.0.0.1:${OIDC_PORT}` (default 8090) | Mock OIDC provider, issuer `http://localhost:8090/seed` |

The database and roles are created from the `DB_*` values in `.env` only when the data volume is
empty (the first start). After changing any `DB_*` value, or after a first start that failed,
recreate the volume with `docker compose --env-file .env -f infrastructure/compose.yaml down -v`
and start again (step 7).

**Demo users.** The mock provider issues tokens with a `roles` claim and audience `seed-api`:

| User | `roles` claim |
|---|---|
| `demo-user` | `["USER"]` |
| `demo-admin` | `["ADMIN"]` |

On the provider's login page, type the user name (`demo-user` or `demo-admin`); see step 5b for
signing in from Swagger UI. To get a token from the command line:

```sh
curl -s -d grant_type=client_credentials -d client_id=demo-admin -d client_secret=x \
  http://localhost:8090/seed/token
```

**Port already in use?** If 5432 or 8090 is taken, change `POSTGRES_PORT` / `OIDC_PORT` in `.env`,
then also change `DB_PORT` and the port in `OIDC_ISSUER_URI` to match, and run the `up -d` command
again.

### 4. Configure object storage

No object-storage service is started locally yet; a later story adds one to
`infrastructure/compose.yaml`. Nothing else depends on it today, so you can leave the `STORAGE_*`
values in `.env` as placeholders.

To use an S3-compatible store you already have (MinIO, AWS S3, …), set in `.env`:

| Variable | Meaning |
|---|---|
| `STORAGE_ENDPOINT` | Base URL of the store, e.g. `http://localhost:9000` |
| `STORAGE_BUCKET` | Bucket name (default `seed-artifacts-local`); create it in the store |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | Credentials with read/write access to that bucket |
| `STORAGE_PATH_STYLE` | `true` for MinIO and most self-hosted stores, `false` for AWS virtual-hosted-style URLs |

### 5. Build and test the backend

```sh
(cd backend && ./gradlew clean build)
```

This needs Docker running (the tests start PostgreSQL with Testcontainers, from the image pinned in
`infrastructure/compose.yaml` and with its `01-roles.sh`). It compiles with the JDK 21 toolchain,
runs the tests (`test`: `ModularityTest`,
`ArchitectureTest`, the boundary fixture test and every other class except `*IT`;
`integrationTest`: the `*IT` classes against a running server; `localProfileTest`:
`LocalReadableLogIT` and `LocalDocsSecurityIT`, in their own JVM), checks formatting with Spotless and fails on a dependency
version conflict in a module the BOMs do not manage. Fix formatting with
`(cd backend && ./gradlew spotlessApply)`. `checkOpenApi` (part of `build`) fails when the
committed API contract `backend/openapi/api.yaml` differs from the one `integrationTest` generates;
after an intended API change, run `(cd backend && ./gradlew updateOpenApi)` and commit the file.
`(cd backend && ./gradlew bootJar)` builds the layered
jar `backend/build/libs/seed-0.0.1-SNAPSHOT.jar`; `(cd backend && ./gradlew bootRun)` starts the
application on port 8080, with health probes (`/actuator/health/liveness`, `/readiness`) and
Prometheus metrics (`/actuator/prometheus`) on management port 8081. Logs are ECS JSON except in
the `local` profile, which writes readable lines prefixed with `[<correlationId>]`. The OpenAPI
document (`/v3/api-docs`) and Swagger UI are served only when `SEED_API_DOCS_ENABLED=true`; the
`local` and `dev` profiles default it to `true`, prod leaves it unset. Every dependency and plugin version is pinned in
`backend/gradle/libs.versions.toml`; the Gradle version is in
`backend/gradle/wrapper/gradle-wrapper.properties`, the JDK in `.sdkmanrc` and the toolchain.

### 5a. Run the backend and migrate the database

The backend reads the `DB_*` and `DB_OWNER_*` variables (see `.env.example`; `DB_SSLMODE` sets the
JDBC `sslmode`, `prefer` by default, and is `require` or `verify-full` in shared environments); `bootRun` does not
read `.env` itself, so export it into the shell first (a value written with Compose's `$$` escape
must be exported by hand, since the shell reads `$$` differently). With the `local` profile, start PostgreSQL
with `docker compose` (step 3) before `bootRun`: Flyway migrates on startup as `DB_OWNER_USERNAME`
(`seed_owner`), then the application runs as `DB_USERNAME` (`seed_app`, DML only).

```sh
docker compose --env-file .env -f infrastructure/compose.yaml up -d postgres
set -a && . ./.env && set +a
(cd backend && ./gradlew bootRun --args='--spring.profiles.active=local')
curl -s localhost:8081/actuator/health/readiness    # {"status":"UP"}
```

Readiness is UP only when the database is reachable, every migration on the classpath is
applied and the operation status listener is listening (step 5c). Outside `local`, the
application never migrates: shared environments run the image once with the `migrate` profile
before the new version starts. That run needs `DB_*` and `DB_OWNER_*`, starts no web server,
migrates as `DB_OWNER_USERNAME` and exits 0 on success and 1 when a migration fails:

```sh
java -jar backend/build/libs/seed-0.0.1-SNAPSHOT.jar --spring.profiles.active=migrate
```

**This release (story 7) is not rolling-compatible:** its migration
`V202611161600__common_add_idempotency_caller.sql` scopes `Idempotency-Key` records to the caller,
which the previous release's instances cannot write. Stop every old instance before the `migrate`
run, then start the new version. (There is no expand/contract step because the seed has no shared
deployment yet.)

Writes (`POST`, `PUT`, `PATCH`, `DELETE`, except form and multipart posts) accept an
`Idempotency-Key` header (api-standards.md §1): a repeat with the same key and request replays the
stored response, and a repeat while the first is still running gets 409
`IDEMPOTENCY_REQUEST_IN_PROGRESS` with `Retry-After: 1`. Settings: `seed.idempotency.retention`
(default `24h`), `seed.idempotency.abandon-after` (default `5m`),
`seed.idempotency.max-stored-response-size` (default `1MB`; a larger response is sent but not
stored, so a retry runs again) and `seed.idempotency.cleanup-cron` (default `0 0 * * * *`, hourly;
`-` turns the cleanup off). A keyed request body has only the global body limit below.

Keys are scoped to the caller: the same key from two users is two independent requests. 401, 403,
408 and 429 answers are never stored, so a retry runs again. A replay needs a valid token but does
not re-check `@PreAuthorize`: a user who lost a role within the retention window can still replay
their own earlier successful response (nothing new is written).

Request limits (security.md §6), set through `.env.example`'s variables:

- `SEED_MAX_BODY_SIZE` (default `1MB` = 1,048,576 bytes): the largest request body that is not
  `multipart/form-data` (other multipart types included); larger gets 413 `PAYLOAD_TOO_LARGE`
  ("Request body exceeds 1MB"), before authentication when the `Content-Length` says so. It sets
  `seed.limits.max-body-size`, and through it Tomcat's form-post limit.
- `SEED_MAX_UPLOAD_SIZE` (default `10MB`): the upload limits of `multipart/form-data`, per file and
  per request; larger gets 413 `PAYLOAD_TOO_LARGE` ("Upload exceeds 10MB"). The per-request limit
  counts the multipart boundaries and part headers too, so a single file must be a little smaller
  than it (a file of exactly 10 MB is refused). It also sets Tomcat's swallow size: a refused body
  is read to its end up to that size so the client gets the 413, and a larger one may get a
  connection reset instead.
- `SEED_WRITES_PER_MINUTE` (default `60`, `seed.limits.writes-per-minute`): the `POST`, `PUT`,
  `PATCH` and `DELETE` requests one caller may make per minute (a burst of the whole budget, then
  a steady refill); over it gets 429 `RATE_LIMITED` with `Retry-After` in seconds. Reads are never
  limited; idempotent replays count. **The count is kept in each instance's memory**, so with N
  instances a caller can make up to N × the limit.

### 5b. Sign in and call the API

The backend is an OIDC resource server (security.md): every API call needs a bearer access token
from the issuer, signed by its keys (JWKS, found through `OIDC_ISSUER_URI` on the first token), with
audience `OIDC_AUDIENCE`, and not expired (30 s clock skew). Roles come from the claim named by
`OIDC_ROLES_CLAIM` (default `roles`; a dotted path such as `realm_access.roles` reads a nested
claim); `ADMIN` implies `USER`. Startup fails when `OIDC_ISSUER_URI` or `OIDC_AUDIENCE` is unset.
Browsers may call the API only from the origins in `CORS_ALLOWED_ORIGINS` (comma-separated, no
wildcard). There are no sessions, cookies or login endpoints. A missing or invalid token gets 401
`UNAUTHENTICATED` with `WWW-Authenticate: Bearer`; a missing role gets 403 `FORBIDDEN`.

Locally, with the compose stack and the `local` profile running (step 5a), get a token for a demo
user from `mock-oauth2-server` and call the API with it:

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=demo-user -d client_secret=x \
  http://localhost:8090/seed/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/anything                 # 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  localhost:8080/api/v1/anything                                                         # 404
```

**Swagger UI** (`http://localhost:8080/swagger-ui/index.html`, `local` and `dev` only) and the
OpenAPI document are readable without signing in. To try an endpoint, press **Authorize**, choose
`oidc` and sign in at the mock provider with the authorization code flow and PKCE (client
`SWAGGER_UI_CLIENT_ID`, default `seed-swagger`, no secret): type `demo-user` as the user name.
`infrastructure/oidc/config.json` gives `seed-swagger` tokens the subject `demo-user`, the
`seed-api` audience and the `USER` role; to act as an administrator, also enter
`{"sub":"demo-admin","roles":["ADMIN"]}` as the claims. Calls made without authorizing answer 401.
Alternatively, paste a token from the command above into `bearer-jwt`.

With a real identity provider, register `SWAGGER_UI_CLIENT_ID` as a public client (no secret, PKCE)
whose allowed redirect URIs include `<api origin>/swagger-ui/oauth2-redirect.html` (for example
`https://api.example.com/swagger-ui/oauth2-redirect.html`), and let the issuer accept CORS requests
from the API's origin: Swagger UI fetches the discovery document and exchanges the code for a token
from the browser.

**Management port.** Health (`/actuator/health`, `/liveness`, `/readiness`) and
`/actuator/prometheus` on port 8081 answer anyone who can reach the port, so **the management port
must be network-restricted** (never exposed publicly). `/actuator/loggers` (change log levels at
run time) needs an `ADMIN` token, and only `ADMIN` sees health components and details:

```sh
ADMIN=$(curl -s -d grant_type=client_credentials -d client_id=demo-admin -d client_secret=x \
  http://localhost:8090/seed/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -H "Authorization: Bearer $ADMIN" localhost:8081/actuator/loggers    # 200
```

Migrations live in `backend/src/main/resources/db/migration/<module>`
(`V<yyyyMMddHHmm>__<module>_<verb>_<object>.sql`, data-and-migrations.md §11); a feature module
adds its location to `spring.flyway.locations` and its schema to `spring.flyway.schemas`, and its
first migration grants `USAGE` on its schema to `${app_role}`. Before merging a branch, re-stamp
its new migration files with a current timestamp: a version older than one already applied is
ignored (`outOfOrder=false`), which fails the `migrate` run and keeps readiness DOWN.

### 5c. Long-running work and housekeeping jobs

A long-running request (the sample's `POST /api/v1/sample-records/export`) answers 202 with an
operation: `Location: /api/v1/operations/{id}` and `data {operationId, status: QUEUED, version: 1,
statusTopic}`. The work runs after commit on the async executor (`spring.task.execution.pool.*`:
4 to 8 threads, queue 200), with the request's correlation ID, user and actor carried over. Follow it
with `GET /api/v1/operations/{id}` (its requester or an ADMIN; others get 403): the status goes
`QUEUED` → `RUNNING` → `SUCCEEDED` or `FAILED`, and `version` rises by one on each change. A
failing work is retried up to 3 times (`seed.operations.retry-delay`, default `1s`, doubling), then
the operation is `FAILED` with `errorCode OPERATION_FAILED`. Events are kept in
`common.event_publication` until their listener finishes. A failed one is resubmitted on restart
and by a job; one stuck for 5 minutes before its listener started, or 15 minutes while it ran
(`spring.modulith.events.staleness.*`), is first marked failed. A listener uses
`@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)` and runs its work through
`OperationListenerSupport` (see `SampleExportListener`).

**Live status over WebSocket** (api-standards.md §9). Every status change is pushed to the
operation's `statusTopic`, so a client need not poll. The endpoint is STOMP 1.2 over plain
WebSocket (no SockJS) at `/ws` on the application port (`ws://` locally, `wss://` in shared
environments), on an in-memory broker; every instance forwards the changes it hears through
PostgreSQL `LISTEN operation_status` to its own clients, so no sticky sessions are needed.

| Frame / destination | Rule |
|---|---|
| `CONNECT` | `Authorization: Bearer <access token>` header (never in the URL), checked like a REST call; optional `X-Correlation-Id` (same format as REST, else a new UUIDv7) names the session in the logs; send `heart-beat:10000,10000` |
| `SUBSCRIBE /topic/operations/{id}` | live pushes; the operation's requester or an `ADMIN` only. Subscribe first… |
| `SUBSCRIBE /app/operations/{id}` | …then this one-off snapshot (same payload); keep the highest `version` seen |
| `SUBSCRIBE /topic/system/resync` | any session; `{"reason":"listener-reconnected"}` means pushes may have been missed: re-read the snapshots you follow, after a random delay of a few seconds (jitter), so all clients do not re-read at once |
| `SEND` | refused: the channel is push-only |

**Heart-beats:** the server sends heart-beats only when the client asks for them, so a client
must request `heart-beat:10000,10000` in `CONNECT` (and send its own every 10 s); a quiet
connection without them is cut by the load balancer's idle timeout.

A push is the operation without the envelope: `{operationId, kind, subjectId, status, version,
result, errorCode, errorMessage, updatedAt, correlationId}`, where `correlationId` is the one of
the request that started the operation. A session's frames are handled in the order they
arrive. A refused frame gets an `ERROR` frame whose `message` header is only a code, and the
connection closes:

- `unauthorized`: no or invalid token on `CONNECT`, a second `CONNECT`, or any frame before it;
- `token-expired`: a token whose `exp` has passed, on `CONNECT` or later (sessions are also
  checked every `seed.realtime.token-check-interval`);
- `forbidden-destination`: another user's operation, a destination outside the table, any `SEND`;
- `not-found`: no such operation, or not a UUID;
- `too-many-subscriptions`: a 51st `/topic` subscription (`UNSUBSCRIBE` frees a slot; snapshots
  hold none);
- `internal-error`: unexpected, logged with the session's correlation ID.

A WebSocket message over the session's limit (16 KB plus a little) closes the connection with
status 1009; a STOMP frame just over 16 KB that still fits it gets `ERROR internal-error`. A client
that falls 15 s or 512 KB behind in reading is disconnected. On shutdown every session is closed
with status 1012 (service restart): reconnect, resubscribe and re-read the snapshots. The
handshake accepts requests without `Origin` (non-browser clients), same-origin ones and those
from `CORS_ALLOWED_ORIGINS`; any other origin gets 403. Readiness includes
`operationStatusListener` (DOWN while the instance cannot listen and is reconnecting, 1 s doubling
to 30 s), and `seed_ws_sessions_active` counts connected sessions.

**Database connections:** each instance holds one connection for `LISTEN` outside the pool
(`DB_*`, application name `seed-operation-status-listener`), so count one more than the pool size
per instance. `LISTEN` needs a direct or session-mode connection: it does not work through
PgBouncer in transaction-pooling mode.

Settings: `seed.realtime.token-check-interval` (`SEED_REALTIME_TOKEN_CHECK_INTERVAL`, default
`30s`): how often sessions are checked; one whose token's `exp` has passed gets `ERROR
token-expired` and is closed, so a client reconnects with a fresh token. The checks share a
scheduler of two threads (`spring.task.scheduling.pool.size`) with the housekeeping jobs. **The
load balancer or ingress in front of the API must forward the `Upgrade` and `Connection` headers
on `/ws` and allow an idle timeout of at least 60 s** (heart-beats every 10 s keep a quiet
connection open).

Housekeeping jobs run on one instance at a time (a PostgreSQL advisory lock) as
`system:job:<name>`, each run with its own correlation ID `job:<name>:<uuid>` and the
`seed_job_duration_seconds` metric. Their schedules (Spring cron; `-` turns a job off):

| Job | Default | Property (environment variable) |
|---|---|---|
| `idempotency-cleanup` | hourly | `seed.idempotency.cleanup-cron` |
| `operation-cleanup` (finished operations created before `seed.jobs.operation-retention`, `7d`) | daily 03:30 | `seed.jobs.operation-cleanup-cron` (`SEED_OPERATION_CLEANUP_CRON`) |
| `event-publication-cleanup` (completed publications older than `seed.jobs.event-publication-retention`, `7d`) | daily 03:45 | `seed.jobs.event-publication-cleanup-cron` (`SEED_EVENT_PUBLICATION_CLEANUP_CRON`) |
| `event-publication-resubmit` (failed publications older than `seed.jobs.event-publication-resubmit-after`, `5m`) | every 5 minutes | `seed.jobs.event-publication-resubmit-cron` (`SEED_EVENT_PUBLICATION_RESUBMIT_CRON`) |

The `migrate` run schedules nothing and resubmits no event.

### 6. Build and test the frontend

```sh
(cd frontend && npm ci && npm run format:check && npm run lint && npm run test:ci && npm run build)
(cd frontend && npm start)    # dev server on http://localhost:4200
```

Each step runs in a subshell, so you stay in the repository root. Inside `frontend/`,
`npm run format` fixes Prettier formatting and `npm test` runs the unit tests in watch mode.

### 7. Stop, reset and restart

```sh
docker compose --env-file .env -f infrastructure/compose.yaml down       # stop, keep data
docker compose --env-file .env -f infrastructure/compose.yaml down -v    # stop and delete all data
docker compose --env-file .env -f infrastructure/compose.yaml up -d      # clean start, no manual steps
```

### 8. Verify items

Points that could not be fully confirmed when the seed was built. Check them when you adopt or
upgrade the seed.

- **google-java-format version.** The spec pins google-java-format 1.37.0, but it fails under the
  Spotless Gradle plugin 8.11.0 (the formatter step throws for every file).
  `backend/gradle/libs.versions.toml` uses 1.36.1, the newest version that works. Move to 1.37.x
  once Spotless supports it.
- **Gradle version.** The wrapper pins Gradle 9.8.2 (`distributionSha256Sum` and the wrapper jar
  checked against services.gradle.org). Re-check the jar with
  `shasum -a 256 backend/gradle/wrapper/gradle-wrapper.jar`; expected
  `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`. Spring Boot 4.1 documents
  support for Gradle 8.14+ and 9.x; confirm a newer Gradle release against the Boot system
  requirements before moving to it.
- **Dependency convergence with the Boot BOM.** `failOnVersionConflict()` applies to every
  configuration. With the BOMs imported through plain `platform()`, it fails on the Boot platform's
  own resolution (each managed module whose transitive requests differ from the BOM, e.g. `jspecify`
  1.0.1 vs 1.0.0, counts as a conflict), on the runtime and test classpaths as much as anywhere, so
  narrowing it would not help. `backend/build.gradle.kts` therefore imports the Boot and Modulith
  BOMs with `enforcedPlatform()`, so only conflicts in modules the BOMs do not manage fail the
  build. Unlike Maven, a BOM version wins even over an explicitly declared version, silently. To
  override one (for example a CVE fix), add `resolutionStrategy.force("group:artifact:version")`
  inside `configurations.configureEach` in `backend/build.gradle.kts`, with the version in the
  catalog; a `strictly` constraint fails to resolve. Revisit if Gradle adds a platform-aware
  convergence check.
- **Frontend versions held back by the 7-day package-age gate.** The repository's supply-chain
  guard (Snap guardrails) rejects npm releases younger than 7 days, so some spec pins were replaced
  by the newest release that passes it, and `package-lock.json` was resolved as of 2026-11-06:
  `@angular/cli` and `@angular/build` 21.2.29 (spec: 21.2.30), `jsdom` 31.0.0 (spec: 31.0.1),
  `prettier` 3.9.10 (spec: 3.10.1). `package.json` also overrides `rollup` to 4.66.0 because
  rollup 4.67+ pulls in `@rollup/rollup-android-x64`, a package younger than the 30-day new-package
  limit. Move to the spec versions and drop the override once they pass the gate.
- **npm install scripts are off.** `frontend/.npmrc` sets `ignore-scripts=true`; esbuild, lmdb,
  msgpackr-extract, @parcel/watcher and fsevents install without their scripts, and lint, tests and
  build pass that way. Remove the setting only if a future dependency needs its install script.
- **`docker compose` path.** The seed was verified with Podman 6 and `podman compose`; the Docker
  Compose v2 path uses the same file and only standard Compose features, but was not run.

## The sample module

**DEMO ONLY.** `com.company.seed.feature.sample` is a small, complete feature module that exercises
the whole backend foundation end to end, so there is a working pattern to copy:

| What | Where it shows |
|---|---|
| CRUD over `/api/v1/sample-records` in the envelope (`ApiResponses`), `201` + `Location` on create | `api/SampleRecordController` |
| Bean Validation → 400 `VALIDATION_FAILED` with `errors[]` | `api/CreateSampleRecordRequest`, `UpdateSampleRecordRequest`; [`docs/validation-rules.md`](docs/validation-rules.md) |
| Role checks: `USER` reads and writes, `ADMIN` deletes and restores (`@PreAuthorize` with `Roles`) | `api/SampleRecordController` |
| Paging and whitelisted sorting (`PageQuery`, `@Sortable`, `PageResult`) | `GET /api/v1/sample-records?page=0&size=20&sort=name,asc` |
| Auditing, optimistic locking (`version`, 409 `CONCURRENT_MODIFICATION`), soft delete and an ADMIN-only native restore | `application/SampleRecordService`, `infrastructure/persistence/JpaSampleRecordRepository` |
| A unique name mapped to 409 `SAMPLE_RECORD_ALREADY_EXISTS` (`ConstraintErrorMapping`) | `config/SampleConstraintErrors`, `db/migration/sample/` |
| The Repository Pattern: a plain-Java aggregate and repository in `domain/`, JPA and Spring Data only in `infrastructure/persistence/` | `domain/`, `infrastructure/persistence/` |
| Tests at every level | `backend/src/test/java/com/company/seed/feature/sample/` (`*ApiIT`, `*PersistenceIT`, `*ServiceTest`, `SampleModuleTests`, the OpenAPI assertion) |

The sample has no per-record ownership: any `USER` may read and change any record. A feature that
needs it checks ownership in its application service, comparing the record's owner with
`CurrentActor.id()` and throwing `ForbiddenOperationException` (403) on a mismatch.

**Removing it.** A real project deletes the module before adding its own. These steps leave a
passing build:

1. Delete `backend/src/main/java/com/company/seed/feature/sample/` and
   `backend/src/test/java/com/company/seed/feature/sample/`.
2. Delete `backend/src/main/resources/db/migration/sample/`.
3. In `backend/src/main/resources/application.yml`, remove `classpath:db/migration/sample` from
   `spring.flyway.locations`, `sample` from `spring.flyway.schemas`, and the comment line above
   them that mentions the sample.
4. In `docs/validation-rules.md`, delete the section "Sample records (DEMO ONLY …)" with its
   table; keep the introduction for the next feature's rows.
5. In this README: delete the link "([The sample module](#the-sample-module))" in the
   introduction; in "Implemented so far", replace "… IDs), security (OIDC resource server, roles,
   `GET /api/v1/me`), operations and housekeeping jobs (`GET /api/v1/operations/{id}`), the live
   operation status over WebSocket (STOMP at `/ws`) and the sample feature's backend (DEMO ONLY),
   including its export." with "… IDs), security (OIDC resource server, roles, `GET /api/v1/me`),
   operations and housekeeping jobs (`GET /api/v1/operations/{id}`) and the live operation status
   over WebSocket (STOMP at `/ws`)."; and delete this whole "The sample module" section.
6. Regenerate the API contract and build: `(cd backend && ./gradlew updateOpenApi && ./gradlew
   clean build)`, then commit `backend/openapi/api.yaml`.

Nothing else names the sample: shared tests take the migration locations and schemas from
`application.yml`.

**A database that already ran the sample migration** (`V202611170900`) no longer starts after the
removal: with `validate-on-migrate` Flyway fails on an applied migration it cannot find ("applied
migration not resolved locally"). For each such database (local volumes included), as
`seed_owner`: drop the schema (`DROP SCHEMA sample CASCADE;`), then either run `flyway repair`
once (it marks the missing migration as deleted in the history) or start the next migrate run once
with `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:missing'`. Locally, `docker compose … down -v`
(step 7) is the simplest way out.

## Backend module boundaries

- Package root `com.company.seed`; modules `config`, `common` (open, shared) and `security`
  (shared) sit directly under it. Feature modules go under `com.company.seed.feature.<module>`;
  `feature` itself is not a module.
- Detection is explicit (`spring.modulith.detection-strategy=explicitly-annotated`): a package is a
  module only if its `package-info.java` carries `@ApplicationModule`.
- Spring Modulith names a feature module by its path below `com.company.seed`, e.g.
  `feature.sample`. Use that name wherever a module is referenced by name.
- The layering rules (`backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java`)
  keep `domain/` and `application/` free of web, persistence and adapter types, keep Spring Data
  interfaces in `infrastructure/persistence/` and without `delete*` methods (deletion is a soft
  delete), keep repository interfaces in `feature/<module>/domain/`, and forbid field injection and
  Jackson 2 databind. Deliberately broken fixtures under
  `backend/src/test/java/com/company/fixtures/` prove each rule fails when broken.
