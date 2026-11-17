---
id: SPEC-monorepo-seed
companions:
  - stack.md
  - architecture-diagrams.md
  - backend-architecture.md
  - data-and-migrations.md
  - api-standards.md
  - errors-and-logging.md
  - security.md
  - realtime-and-storage.md
  - frontend-architecture.md
  - quality-and-delivery.md
  - conventions.md
  - decisions.md
  - roadmap.md
sources: []
rules_touched: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Monorepo seed — Spring Boot + Angular foundation

## Why

**Vision plus pain.** Every new project re-implements the same plumbing: response format, errors, validation, auditing, security, migrations, logging, quality gates and local setup, each time slightly differently. This seed is one reusable monorepo with a Spring Boot backend and an Angular frontend in which all of that is already built, tested and documented. A project copies it, deletes the sample feature, and adds only its own business modules. The seed contains no business logic and no project-specific configuration.

## Capabilities

- **CAP-1**
  - **intent:** A developer can start the whole system locally from a clean checkout using documented commands.
  - **success:** On a machine with Docker, JDK 21 and Node 24 only, following the README brings up PostgreSQL, MinIO and the mock OIDC provider via `infrastructure/compose.yaml`, then starts the backend and frontend. The health endpoint returns UP, Swagger UI opens, and sign-in works with documented demo users. A teardown and restart works without manual clean-up.
- **CAP-2**
  - **intent:** Package and module boundaries are enforced in every build, and a new feature module can be added without touching unrelated code.
  - **success:** `ApplicationModules.verify()` and the ArchUnit rules pass. A fixture that imports another module's internals fails the build, and so do an `application/` or `domain/` class importing `api`, `infrastructure`, JPA or web types, and an `api/` class using a repository. Adding module `x` needs only package `com.company.seed.feature.x`, a `db/migration/x` folder and schema `x`.
- **CAP-3**
  - **intent:** Every REST endpoint answers in one standard envelope for success, paged success, empty/delete and error.
  - **success:** A contract test over the route table asserts that every 2xx body has `success,message,data,timestamp,correlationId` and every 4xx/5xx body has `success=false,status,message,errorCode,errors,timestamp,path,correlationId`, including 401/403 written by the security filters.
- **CAP-4**
  - **intent:** Exceptions are handled in one place and never leak internals.
  - **success:** Each exception in the catalogue maps to its documented status and `errorCode` (validation, 401, 403, 404, 409, 415, 500 and the rest). No response contains a stack trace, SQL or class name. Every 5xx is logged once at ERROR with its correlation ID.
- **CAP-5**
  - **intent:** Input is validated at the request edge and in the domain, field errors come back in a standard shape, and the frontend applies the same rules.
  - **success:** An invalid DTO, including nested and group-specific cases, returns 400 `VALIDATION_FAILED` with one `errors[]` entry per field path. The frontend maps those entries onto the matching form controls. Each shared rule has a matching pair of frontend and backend tests.
- **CAP-6**
  - **intent:** Records carry audit fields filled in automatically, and records can be soft-deleted and restored.
  - **success:** Creating or updating through the API sets `created_by`/`updated_by` to the token subject and the timestamps in UTC. Scheduled or system work records `system:<name>`. A soft-deleted row is absent from normal reads and present again after restore. Audit fields cannot be set through any request DTO.
- **CAP-7**
  - **intent:** The database schema is changed only by versioned migrations, applied once per deployment.
  - **success:** Hibernate runs with `ddl-auto=validate`. Locally and in tests, Flyway migrates on startup against PostgreSQL. In shared environments, a one-off `migrate` run applies pending migrations and exits 0 before the app starts. The app reports readiness DOWN when migrations are pending; when a mapped table is missing, startup fails (`ddl-auto=validate`; decided 2026-11-16).
- **CAP-8**
  - **intent:** Every endpoint is protected by OIDC-issued tokens, denied by default, with role checks on the server.
  - **success:** For every endpoint in the route table, no token gives 401, a token without the role gives 403, and a valid token gives 2xx. The sample matrix holds: health is public, sample read needs `USER` or `ADMIN`, sample delete needs `ADMIN`. A controller method without `@PreAuthorize` fails the build.
- **CAP-9**
  - **intent:** One correlation ID follows each request through browser, backend logs, responses and pushes, and logs are structured with sensitive values masked.
  - **success:** A request with `X-Correlation-Id: abc` produces JSON log lines carrying `abc`, `abc` in the response header and envelope, and `abc` in any status push it causes. Tokens, passwords and secrets never appear in log output (test asserts).
- **CAP-10**
  - **intent:** Long-running work reports its status to the browser live, whichever backend instance the browser is connected to.
  - **success:** Starting the sample export returns 202 with an `operationId`. A STOMP subscriber on `/topic/operations/{id}` receives `RUNNING` then `SUCCEEDED` through any of two running instances, never an older version after a newer one. After a reconnect, a snapshot restores the latest state.
- **CAP-11**
  - **intent:** Files are stored in S3-compatible object storage through one port and downloaded through short-lived links, without exposing the bucket.
  - **success:** Against MinIO (Testcontainers), storing a file leaves a `STORED` metadata row whose `sha256` matches the object. `GET /api/v1/artifacts/{id}/download-url` returns a URL that downloads the file and stops working after its TTL. A failure between row and object leaves no `STORED` row, and the cleanup job removes the `PENDING` row. No AWS SDK type appears outside `common.storage.infrastructure`.
- **CAP-12**
  - **intent:** The Angular app has a standalone, lazy-loaded shell with a layout, a typed API layer and central HTTP and error handling.
  - **success:** A production build emits one chunk per feature. Components never build raw backend URLs. 401 leads to sign-in and 403 to `/forbidden`; other errors become typed errors or notifications. The generated client has no drift.
- **CAP-13**
  - **intent:** Forms are built from one reusable Reactive Forms kit covering validation, messages, server errors, submit and edit modes.
  - **success:** The sample form demonstrates sync, async, cross-field and conditional validators, and maps server `errors[]` onto controls. On an invalid submit it marks all controls touched and focuses the first invalid one. It shows a pending state and resets to its loaded values in edit mode. All of this is unit-tested.
- **CAP-14**
  - **intent:** Styling comes from one token system with component-scoped SCSS and accessible defaults.
  - **success:** stylelint passes with `color-no-hex` outside `src/styles/` and no `::ng-deep`. Changing a token changes every component and Material theme value that uses it. Keyboard navigation and labels pass the angular-eslint accessibility rules.
- **CAP-15**
  - **intent:** The API contract is generated from code, committed, and used to generate the frontend client.
  - **success:** `./gradlew build` writes `backend/openapi/api.yaml`, including the envelope schemas. CI fails when the committed file or `frontend/src/app/api/` differs from the regenerated one. Swagger UI is on in `local`/`dev` and off in `prod`.
- **CAP-16**
  - **intent:** Each layer is tested at the right level, and tests are repeatable without a developer's database or the internet.
  - **success:** CI runs every level in [quality-and-delivery.md](quality-and-delivery.md) and passes. Integration tests use PostgreSQL and MinIO Testcontainers.
- **CAP-17**
  - **intent:** Code quality is gated on every pull request.
  - **success:** A PR that misses the Sonar quality gate, fails lint or breaks a module boundary fails CI, and merge to `main` is blocked. When no Sonar token is configured, the workflow reports the analysis as skipped instead of passing silently.
- **CAP-18**
  - **intent:** Backend and frontend build and test independently, in CI and locally.
  - **success:** A PR touching only `frontend/` runs only frontend jobs; one touching only `backend/` runs only backend jobs. A change to `backend/openapi/api.yaml` also runs the frontend client-drift check. The single required check `ci-gate` passes when the skipped side did not run.
- **CAP-19**
  - **intent:** The application reports liveness, readiness and metrics.
  - **success:** `/actuator/health/liveness` and `/readiness` answer; readiness goes DOWN while the database is unreachable or the schema is behind. The metrics listed in [quality-and-delivery.md](quality-and-delivery.md) are exported. Health details are hidden from anonymous callers.
- **CAP-20**
  - **intent:** A small, clearly labelled sample feature proves the whole foundation end to end and can be deleted cleanly.
  - **success:** The `sample` feature exercises create, read, update, soft delete, restore, paging, validation, auditing, RBAC, async export with live status, and an S3 download. Deleting `feature/sample`, its migration folder and `frontend/src/app/features/sample` leaves a build that compiles and passes every check.

## Constraints

- **Stack pinned:** Java 21 LTS, Spring Boot 4.1.x, Spring Modulith 2.1.x, PostgreSQL 16, Gradle wrapper with Kotlin DSL, Angular 21, TypeScript 5.9, Angular Material 21. Versions and compatibility are in [stack.md](stack.md).
- **Generic only:** no business modules, domain rules, project names, project-specific roles or environment endpoints. Everything project-specific is a placeholder in `.env.example` or `config.json`.
- **One backend application, one image.** Profiles `local`, `test`, `dev`, `qa`, `prod` select configuration; `migrate` runs Flyway once and exits. No worker role, no message broker.
- **Seed backend layout:** `config/`, `common/`, `security/`, `feature/<module>/{api,application,domain,infrastructure}` under `com.company.seed` ([backend-architecture.md](backend-architecture.md) §1, §4). Application and domain packages import no `api`, `infrastructure`, JPA, web or vendor SDK type.
- **Repository Pattern:** one `<Aggregate>Repository` interface per aggregate root in `domain/`, implemented once in `infrastructure/persistence/`; application services use only the interface; JPA entities and Spring Data types never cross it; no generic base repository ([backend-architecture.md](backend-architecture.md) §5).
- **Module boundaries:** modules talk only through another module's root package (facade, event records) or its events. Each module owns its schema. There are no FKs or JPA relations across schemas.
- **Every REST response uses the envelope** in [api-standards.md](api-standards.md). WebSocket messages and Actuator are the only exceptions. Error formats are never mixed.
- **Backend is the only authority:** Angular guards and validators are usability aids; every rule and permission is enforced on the server.
- **Secrets never in git, images, YAML or the bundle.** Configuration comes from environment variables; `.env.example` holds placeholders only.
- **Tokens stay in browser memory**, never in `localStorage`.
- No external call (S3, HTTP) is made inside a database transaction.
- **IDs:** UUIDv7 for exposed records, `BIGINT IDENTITY` for internal tables; a bigint ID is never exposed.
- Time comes from an injected UTC `Clock`; persisted moments are `timestamptz` / `Instant`.
- **Avoid overengineering:** add an abstraction only when it has a second implementation or isolates a vendor. No generic base services, controllers or repositories.

## Non-goals

- Business modules, workflows, domain rules or a business data model. Only the removable `sample` feature exists.
- Production deployment, cloud infrastructure provisioning, CD pipelines, secrets-vault integration and hosted observability.
- A specific enterprise identity provider. Any OIDC provider plugs in through configuration.
- Message brokers, a separate worker process, or microservices.
- Local user and password management (no `app_user` / `app_role` tables).
- Server-side sessions or a backend-for-frontend.

## Success signal

On a clean clone, the README's setup steps start the stack, and a demo user can sign in, create, edit, soft-delete and restore a sample record, see field-level validation errors, start a sample export, watch its status change live, and download the result through a presigned link. The same correlation ID appears in the browser, the backend logs and the status push. CI on that change is green across modularity, ArchUnit, Testcontainers, the envelope contract test, OpenAPI drift, lint and the Sonar gate. Deleting the sample feature leaves CI green.

## Assumptions

- The stack is Java 21 / Boot 4.1 / Angular 21 as decided for the original prompt, not the seed document's Java 17 / Boot 3.x. ADR-01 records this.
- The existing response envelope is kept; its `correlationId` field plays the role of the seed document's `traceId`.
- Developers can reach Docker Hub, Maven Central, the Gradle Plugin Portal, services.gradle.org and npm.
- A SonarQube Server or SonarQube Cloud instance may exist; CI works and reports "skipped" without one.
- GitHub Actions is the CI platform; another platform runs the same stages.
- Flyway versions are timestamps (`V202611101200__…`) rather than the plain `V1__` sequence, to avoid collisions between parallel branches.

## Open Questions

- Envelope shape: keep the current `ApiResponse` (pagination beside `data`, `correlationId`), or switch to the seed document's shape (`items` inside `data`, `traceId`), or adopt RFC 9457 Problem Details?
- Which entities in a real project support restore after soft delete, and which role may restore? The sample allows `ADMIN`.
- Coverage thresholds: keep the proposed numbers, or set them after a baseline measurement as the seed document suggests?
- Angular 21 is LTS-only until about mid-2027. When does the seed move to Angular 22/23?
- Which S3-compatible local storage replaces MinIO, whose images are no longer published (SeaweedFS, RustFS, LocalStack)? Decide before the storage story.
