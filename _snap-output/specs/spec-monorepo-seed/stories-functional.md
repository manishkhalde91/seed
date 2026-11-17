# Monorepo seed — Functional Story Breakdown

Companion detail to `stories.yaml`: each entry below maps 1-1 by `id` to the
matching entry in `stories.yaml`, same order. This file carries the
functional shape of each story; `stories.yaml` carries the dispatch fields
(`spec_checkpoint`, `done_checkpoint`, `invoke_dev_with`).

## Story 1: Repository skeleton, local infrastructure and module boundaries

**User Story**

As a developer starting a new project from the seed, I want to clone one monorepo, start its local infrastructure with one command and build both apps independently, so that I begin from enforced module boundaries instead of re-creating plumbing.

**Acceptance Criteria**

_Scenario 1 — local infrastructure_
**GIVEN** a machine with Docker or Podman, JDK 21 and Node 24 only, and a `.env` copied from `.env.example`
**WHEN** the developer follows README "First-time setup" and runs `docker compose -f infrastructure/compose.yaml up -d` (or `podman compose`)
**THEN** `postgres` (with `seed_db`, `seed_owner`, `seed_app`) and the mock OIDC server on port 8090 report healthy
**AND** object storage is configurable through `STORAGE_*` variables only; no storage service is installed in this story
**AND** `docker compose … down -v` followed by `up -d` works without manual clean-up

_Scenario 2 — backend builds alone_
**GIVEN** the `backend/` Gradle project (Kotlin DSL, `gradle/libs.versions.toml`) with the committed wrapper
**WHEN** the developer runs `./gradlew clean build` in `backend/`
**THEN** the build passes, including `ModularityTest` (`ApplicationModules.verify()`, detected modules exactly `config`, `common`, `security`) and the ArchUnit rule set
**AND** the Java toolchain requires JDK 21 and `failOnVersionConflict()` enforces dependency convergence

_Scenario 3 — boundary violations are caught_
**GIVEN** test fixtures that (a) import another module's internal package, (b) make an `application/` or `domain/` class import `api`, `infrastructure`, JPA or web types, (c) make an `api/` class use a repository
**WHEN** the architecture rules are evaluated against each fixture
**THEN** every fixture is reported as a violation by a dedicated test that asserts the failure, so the main build stays green

_Scenario 4 — frontend builds alone_
**GIVEN** the minimal Angular 21 workspace in `frontend/` on Node 24
**WHEN** the developer runs `npm ci && npm run lint && npm run test:ci && npm run build`
**THEN** all four commands pass without touching `backend/`

_Scenario 5 — repository hygiene_
**GIVEN** the repository root
**WHEN** a reviewer inspects it
**THEN** it contains `.editorconfig`, `.gitattributes`, `.gitignore` (ignoring `.env`), `.nvmrc`, `.sdkmanrc`, `.env.example` with placeholders only, README with one setup path, CODEOWNERS, PR template and `docs/adr/` with ADR-01…14
**AND** no secret value is committed

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| Modulith 2.1 does not detect modules under the intermediate `feature/` package | Proven in this story by a test fixture app with modules under `feature/`; if detection fails, record a README "verify" item and raise it at the done checkpoint |
| A pinned version (springdoc 3.x, Flyway starter artifact) cannot be confirmed for Boot 4.1 | Pin the newest confirmed patch and mark it *verify* in the README |
| Ports 5432/8090 already in use locally | Ports configurable via `.env`; README notes how to change them |

**Validation**

- `.env.example` lists every variable in security.md §5 with placeholder values only.
- Package root is `com.company.seed`; every module root has `package-info.java` with `@ApplicationModule`; `feature` has none.

**Assumptions**

- Developers can reach Docker Hub, Maven Central, the Gradle Plugin Portal, services.gradle.org and npm.
- No feature module exists yet; `ModularityTest` must accept additional feature modules later without change.

**Dependencies**

- Spec approved; repository-interface location decided (`domain/`).
- Story 1 was first built on Maven; story 2 migrates that build so Scenario 2 holds.

## Story 2: Migrate the backend build from Maven to Gradle (Kotlin DSL)

**User Story**

As a developer starting a new project from the seed, I want the backend to build with the committed Gradle wrapper and one version catalog, so that I get incremental, cached builds and every pinned version in one place without losing any story 1 check.

**Acceptance Criteria**

_Scenario 1 — backend builds with Gradle_
**GIVEN** `backend/` with `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` and the committed wrapper, on a machine with JDK 21
**WHEN** the developer runs `./gradlew clean build` in `backend/`
**THEN** the build passes, including `ModularityTest`, `ArchitectureTest`, the boundary-violation fixture test and `spotlessCheck`, with the same 27 tests the Maven build ran
**AND** no `pom.xml`, `mvnw`, `mvnw.cmd` or `.mvn/` remains in the repository

_Scenario 2 — build rules carried over from Enforcer_
**GIVEN** the Java toolchain set to 21 with auto-provisioning off and `failOnVersionConflict()` enabled
**WHEN** the build runs without a JDK 21, or with a dependency version conflict
**THEN** it fails with a toolchain or version-conflict message, and no JDK is downloaded

_Scenario 3 — wrapper integrity_
**GIVEN** `gradle/wrapper/gradle-wrapper.properties`
**WHEN** a reviewer inspects it
**THEN** it pins the Gradle distribution and carries `distributionSha256Sum`, so a tampered download fails

_Scenario 4 — test split and packaging_
**GIVEN** the build script
**WHEN** `./gradlew build` runs
**THEN** the `test` task runs `*Test`, an `integrationTest` task runs `*IT` from the same source set, and `check` depends on both
**AND** `bootJar` produces a layered jar

_Scenario 5 — documentation_
**GIVEN** the README and `docs/`
**WHEN** a reviewer searches them for `mvnw` or Maven commands
**THEN** none remain; the setup and run commands use `./gradlew`, and `docs/adr/` contains ADR-15

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| `failOnVersionConflict()` flags conflicts the Boot platform already resolves | Narrow it to the runtime and test classpaths or use an equivalent check; record a README *verify* item (stack.md risk 4) |
| google-java-format 1.37.0 still fails under the Spotless Gradle plugin | Keep 1.36.1 and the existing README *verify* item |
| The newest Gradle release is not supported by the Boot 4.1 plugin | Pin the newest release the plugin supports and mark it *verify* |

**Assumptions**

- Developers can reach Maven Central, the Gradle Plugin Portal and services.gradle.org.

**Dependencies**

- Story 1 done (the Maven build being migrated).

## Story 3: CI pipeline and quality gates

**User Story**

As a team lead, I want every pull request checked by a path-filtered pipeline with a single required gate, so that quality is enforced without running unrelated jobs.

**Acceptance Criteria**

_Scenario 1 — independent jobs_
**GIVEN** a PR touching only `frontend/`
**WHEN** CI runs
**THEN** only the `frontend` job runs and `ci-gate` passes
**AND** a PR touching only `backend/` runs only the `backend` job

_Scenario 2 — contract change_
**GIVEN** a PR changing `backend/openapi/api.yaml`
**WHEN** CI runs
**THEN** the frontend client-drift check also runs

_Scenario 3 — failing gate blocks_
**GIVEN** a PR that fails lint, a module boundary or the Sonar gate
**WHEN** CI finishes
**THEN** `ci-gate` fails

_Scenario 4 — no Sonar token_
**GIVEN** `SONAR_TOKEN` is not configured
**WHEN** CI runs
**THEN** the Sonar step is skipped and the job summary states "skipped"

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| A side's job is skipped by the path filter | `ci-gate` (`if: always()`) fails only when a job that ran failed or was cancelled |

**Dependencies**

- Stories 1 and 2.

**Open Questions**

| Question | Owner | Due date |
| :-- | :-- | :-- |
| Coverage thresholds: keep proposed numbers or set after a baseline? | Spec owner | Before story 3 is closed |

## Story 4: Response envelope, exception handling and validation

**User Story**

As an API consumer, I want every response in one envelope with stable error codes and per-field validation errors, so that clients handle success and failure uniformly.

**Acceptance Criteria**

_Scenario 1 — envelope contract_
**GIVEN** the route table
**WHEN** the contract test calls each route
**THEN** every 2xx body has `success,message,data,timestamp,correlationId` and every 4xx/5xx body has `success=false,status,message,errorCode,errors,timestamp,path,correlationId`

_Scenario 2 — exception catalogue_
**GIVEN** each exception in errors-and-logging.md §2
**WHEN** it is raised by a handler
**THEN** the response has the documented status and `errorCode`, no stack trace, SQL or class name
**AND** each 5xx is logged once at ERROR with its correlation ID

_Scenario 3 — validation_
**GIVEN** an invalid request DTO, including nested and group-specific cases
**WHEN** it is submitted
**THEN** the response is 400 `VALIDATION_FAILED` with one `errors[]` entry per field path

**Dependencies**

- Stories 1 and 2.

**Open Questions**

| Question | Owner | Due date |
| :-- | :-- | :-- |
| Envelope shape: current `ApiResponse`, seed-document variant, or RFC 9457? | Spec owner | Before story 4 starts |

## Story 5: Correlation ID, structured logging, OpenAPI export and health

**User Story**

As an operator, I want one correlation ID across requests, logs and responses, a committed API contract and health endpoints, so that I can trace problems and monitor the app.

**Acceptance Criteria**

_Scenario 1 — correlation_
**GIVEN** a request with `X-Correlation-Id: abc`
**WHEN** it is processed
**THEN** JSON log lines, the response header and the envelope all carry `abc`
**AND** a request without the header gets a generated ID

_Scenario 2 — masking_
**GIVEN** a request carrying a bearer token or password field
**WHEN** it is logged
**THEN** no token, password or secret appears in log output (test asserts)

_Scenario 3 — OpenAPI drift_
**GIVEN** a code change that alters the API
**WHEN** `./gradlew build` runs without updating `backend/openapi/api.yaml`
**THEN** the build fails on drift; Swagger UI is on in `local`/`dev` and off in `prod`

_Scenario 4 — health_
**GIVEN** the app is running
**WHEN** `/actuator/health/liveness` and `/readiness` are called anonymously
**THEN** they answer without details, and readiness is DOWN while the database is unreachable

**Dependencies**

- Story 4 (envelope schemas appear in the OpenAPI file).

## Story 6: Persistence foundation — Flyway, auditing, soft delete, IDs, idempotency

**User Story**

As a developer adding a module, I want migrations, audit fields, soft delete and IDs provided by the foundation, so that every module persists data the same way.

**Acceptance Criteria**

_Scenario 1 — migrations_
**GIVEN** the `local` or `test` profile
**WHEN** the app starts against PostgreSQL
**THEN** Flyway applies migrations and Hibernate validates the schema (`ddl-auto=validate`)
**AND** the `migrate` profile applies pending migrations as `seed_owner` and exits 0

_Scenario 2 — auditing_
**GIVEN** an authenticated actor or a system job
**WHEN** a record is created or updated
**THEN** `created_by`/`updated_by` hold the subject or `system:<name>` and timestamps are UTC; no request DTO can set audit fields

_Scenario 3 — soft delete_
**GIVEN** a soft-deleted row
**WHEN** normal reads run
**THEN** it is absent, and after restore it is present again

_Scenario 4 — idempotency_
**GIVEN** a write with an `Idempotency-Key`
**WHEN** it is repeated with the same body
**THEN** the stored response is replayed; with a different body the response is 409 `IDEMPOTENCY_KEY_REUSED`

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| Schema behind the code in a shared environment | Readiness reports DOWN until `migrate` has run |

**Dependencies**

- Stories 1 and 2; story 7 provides the token subject for auditing (system actor works without it).

## Story 7: Security — OIDC resource server, RBAC and CORS

**User Story**

As a security owner, I want every endpoint denied by default and checked against OIDC tokens and roles, so that no endpoint is accidentally public.

**Acceptance Criteria**

_Scenario 1 — deny by default_
**GIVEN** every endpoint in the route table
**WHEN** it is called with no token, a role-less token and a valid token
**THEN** the responses are 401, 403 and 2xx respectively, in the standard error envelope

_Scenario 2 — role hierarchy_
**GIVEN** a token with role `ADMIN`
**WHEN** a `USER` endpoint is called
**THEN** access is granted

_Scenario 3 — build enforcement_
**GIVEN** a controller method without `@PreAuthorize`
**WHEN** the architecture tests run
**THEN** the build fails

_Scenario 4 — CORS_
**GIVEN** `CORS_ALLOWED_ORIGINS=http://localhost:4200`
**WHEN** a request comes from another origin
**THEN** it is rejected; no wildcard is used with credentials

**Dependencies**

- Stories 1 and 4.

## Story 8: Sample feature backend

**User Story**

As a developer learning the seed, I want a small sample feature that uses every foundation piece, so that I can copy its patterns into real modules.

**Acceptance Criteria**

_Scenario 1 — CRUD and paging_
**GIVEN** a `USER` token
**WHEN** sample records are created, read, updated and listed with `page`, `size` and whitelisted `sort`
**THEN** responses use the envelope with `pagination`; an unknown sort field returns 400 `VALIDATION_FAILED`

_Scenario 2 — delete and restore_
**GIVEN** an `ADMIN` token
**WHEN** a sample record is soft-deleted and then restored
**THEN** it disappears from and reappears in reads; a `USER` token gets 403 on both

_Scenario 3 — Repository Pattern_
**GIVEN** the sample module
**WHEN** the architecture tests run
**THEN** `SampleRecordRepository` lives in `domain/`, `JpaSampleRecordRepository` in `infrastructure/persistence/`, and only `application/` calls it

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| Concurrent update of the same record | 409 `CONCURRENT_MODIFICATION` via optimistic locking |

**Dependencies**

- Stories 4, 6 and 7.

**Open Questions**

| Question | Owner | Due date |
| :-- | :-- | :-- |
| Which entities support restore in real projects, and which role restores? Sample uses `ADMIN`. | Spec owner | Before the first real module |

## Story 9: Live operation status over WebSocket

**User Story**

As a user who starts long-running work, I want to see its status change live, so that I know when the result is ready without refreshing.

**Acceptance Criteria**

_Scenario 1 — start_
**GIVEN** a `USER` token
**WHEN** the sample export is started
**THEN** the response is 202 with an `operationId`

_Scenario 2 — live push across instances_
**GIVEN** two running app contexts and a STOMP subscriber on `/topic/operations/{id}` connected to either
**WHEN** the operation progresses
**THEN** the subscriber receives `RUNNING` then `SUCCEEDED`, never an older version after a newer one

_Scenario 3 — resync_
**GIVEN** a subscriber that reconnects
**WHEN** it requests the snapshot
**THEN** it receives the latest state

_Scenario 4 — subscription auth_
**GIVEN** a user who is not `requested_by` and not `ADMIN`
**WHEN** they subscribe to another user's operation
**THEN** the subscription is rejected

**Edge Cases**

| Edge Case | Proposed Solution |
| :-- | :-- |
| LISTEN connection drops | Reconnect with 1–30 s backoff and publish on `/topic/system/resync` |
| App restarts mid-operation | Event publication registry resubmits the unfinished event |

**Dependencies**

- Stories 7 and 8.

## Story 10: Object storage and sample export download

**User Story**

As a user who ran an export, I want to download the result through a short-lived link, so that I get the file without the bucket being exposed.

**Acceptance Criteria**

_Scenario 1 — store_
**GIVEN** the chosen S3-compatible store, added to `infrastructure/compose.yaml` in this story and used via Testcontainers
**WHEN** a file is stored
**THEN** a `STORED` metadata row exists whose `sha256` matches the object

_Scenario 2 — download link_
**GIVEN** a `STORED` artifact owned by the caller
**WHEN** `GET /api/v1/artifacts/{id}/download-url` is called
**THEN** the returned URL downloads the file and stops working after its 5-minute TTL

_Scenario 3 — failure and cleanup_
**GIVEN** a failure between inserting the row and writing the object
**WHEN** the `artifact-orphan-cleanup` job runs
**THEN** no `STORED` row exists and the stale `PENDING` row is removed

_Scenario 4 — isolation_
**GIVEN** the codebase
**WHEN** the architecture tests run
**THEN** no AWS SDK type appears outside `common.storage.infrastructure`

**Dependencies**

- Story 9 (export operation produces the artifact).

**Open Questions**

| Question | Owner | Due date |
| :-- | :-- | :-- |
| Which S3-compatible local store replaces MinIO (no longer published): SeaweedFS, RustFS or LocalStack? | Spec owner | Before story 10 starts |

## Story 11: Angular shell, authentication, interceptors and design tokens

**User Story**

As a user, I want to sign in and move around a consistent, accessible app shell, so that every feature looks and behaves the same.

**Acceptance Criteria**

_Scenario 1 — sign-in_
**GIVEN** the mock OIDC provider
**WHEN** `demo-user` signs in
**THEN** the shell loads, tokens are held in memory only, and nothing auth-related is in `localStorage`

_Scenario 2 — error routing_
**GIVEN** an API call
**WHEN** it returns 401 or 403
**THEN** 401 starts sign-in keeping the URL and 403 navigates to `/forbidden`; other errors become typed errors or notifications with the correlation ID

_Scenario 3 — build and styles_
**GIVEN** a production build
**WHEN** it runs
**THEN** each feature is its own lazy chunk, budgets pass, stylelint passes with no raw hex outside `src/styles/` and no `::ng-deep`
**AND** the generated API client shows no drift

**Dependencies**

- Story 5 (OpenAPI file) and story 7; a Prism mock may stand in earlier.

**Open Questions**

| Question | Owner | Due date |
| :-- | :-- | :-- |
| When does the seed move from Angular 21 (LTS until about mid-2027) to 22/23? | Spec owner | Before story 11 starts |

## Story 12: Reactive forms kit and sample UI

**User Story**

As a user, I want sample forms that validate as I type and show server errors next to the right field, so that I can fix input quickly.

**Acceptance Criteria**

_Scenario 1 — validators_
**GIVEN** the sample form
**WHEN** the user enters invalid data
**THEN** sync, async, cross-field and conditional validators show messages keyed by the backend `FieldError.code`

_Scenario 2 — invalid submit_
**GIVEN** an invalid form
**WHEN** the user submits
**THEN** all controls are marked touched and the first invalid control is focused

_Scenario 3 — server errors_
**GIVEN** a valid form the server rejects with `errors[]`
**WHEN** the response arrives
**THEN** each error is shown on its control; unmatched errors appear in the form banner

_Scenario 4 — edit and export screens_
**GIVEN** an existing record
**WHEN** the user edits, resets, deletes, restores (as ADMIN) or starts an export
**THEN** reset restores loaded values, save is disabled while pristine, and the export screen shows live status and a download button

**Dependencies**

- Stories 10 and 11.

## Story 13: Clean-checkout verification and sample removal

**User Story**

As a team adopting the seed, I want proof that the README works from a clean clone and that the sample can be deleted cleanly, so that I can trust the seed as a starting point.

**Acceptance Criteria**

_Scenario 1 — success signal_
**GIVEN** a clean clone and only the README
**WHEN** a developer follows it
**THEN** a demo user signs in, creates, edits, soft-deletes and restores a record, sees field errors, starts an export, watches live status and downloads the result
**AND** the same correlation ID appears in the browser, backend logs and status push

_Scenario 2 — sample removal_
**GIVEN** a throwaway branch
**WHEN** `feature/sample`, its migration folder and `frontend/src/app/features/sample` are deleted
**THEN** the build compiles and CI is green

**Dependencies**

- Stories 1–12.
