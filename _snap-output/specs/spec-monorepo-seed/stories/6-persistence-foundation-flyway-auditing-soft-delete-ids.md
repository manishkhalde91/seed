---
title: 'Story 6 (6a + 6b) — Persistence foundation: Flyway, roles, auditing, soft delete, IDs'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_commit: '46fb7130329232361244d69019b28f71dca6167c'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/data-and-migrations.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/errors-and-logging.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The backend has no database. There are no migrations, no audited or soft-deletable base entities, no DB-aware readiness and no persistence error mapping, so no feature can store data (CAP-6, CAP-7).

**Approach:** Packet 6a + 6b (decided 2026-11-16, answer "S"; 6c idempotency is deferred to its own pass).

- **6a:** JPA, Flyway and PostgreSQL wiring, the `common` migration with role grants, a `migrate` profile, a Testcontainers harness that reuses `infrastructure/postgres/init/01-roles.sh`, DB and schema readiness, and the deferred persistence error mappings.
- **6b:** auditing base classes, `CurrentActor`/`SystemActor` and soft delete with restore, proven on a test-only fixture entity.
- `IdGenerator` becomes monotonic within one JVM (decided: option a).
- Requests are audited as `anonymous` until story 7 (decided: option a).

## Boundaries & Constraints

**Always:**
- The data-and-migrations.md rules apply: §1, §3–§7, §9 Hikari values, §10 roles and §11 Flyway.
  - `ddl-auto=validate`; `open-in-view=false`; `hibernate.jdbc.time_zone=UTC`; batch size 50 with ordered inserts.
  - Statement timeout 15 s, set through the JDBC URL `options`.
  - Every SQL object is schema-qualified.
- **Connections:** the app connects as `${DB_USERNAME}`. Flyway runs as `${DB_OWNER_USERNAME}`, with locations `classpath:db/migration/common` (more appended per module), `schemas: common`, and the history table `common.flyway_schema_history`.
- **Flyway settings:** `clean-disabled: true`, `validate-on-migrate: true`, `out-of-order: false`.
- **Where Flyway runs:**
  - `local`, `migrate` and tests: enabled.
  - Default (shared environments): disabled.
- **Grants:** the grants to the app role live in the migration and use the Flyway placeholder `${app_role}` (= `DB_USERNAME`, default `seed_app`): `USAGE` on schema `common`; default privileges `FOR ROLE` the owner (not limited to one schema) giving `SELECT, INSERT, UPDATE, DELETE` on every table and `USAGE, SELECT` on every sequence the owner creates; `SELECT` on `common.flyway_schema_history`. Each later module migration grants `USAGE` on its own schema.
- **`migrate` profile:**
  - It runs no web server, migrates, then closes the context, so the process exits 0.
  - A failed migration fails startup (exit 1).
  - The operator supplies `DB_*` and `DB_OWNER_*`.
- **Readiness group:** `readinessState`, `db` and `schemaVersion`.
  - `schemaVersion` is DOWN when any classpath migration is not applied (pending or ignored), the history holds a failed migration, or the history cannot be read (a database the `migrate` run never touched). It never throws. The DOWN detail names the pending, ignored or failed versions. It reads the history table as the app user, so Flyway's own run need not be enabled.
  - Liveness never includes `db`.
- **Persistence error mapping** in `GlobalExceptionHandler` (errors-and-logging.md §2):
  - `ObjectOptimisticLockingFailureException` → 409 `CONCURRENT_MODIFICATION`, logged at INFO.
  - `DataIntegrityViolationException` with a constraint name found in a `ConstraintErrorMapping` bean → 409 with that bean's code, logged at WARN. Otherwise → 500 `DATA_INTEGRITY_VIOLATION`, logged at ERROR with the stack trace. CAP-4's every-5xx-at-ERROR rule overrides the WARN in errors-and-logging.md §2.
  - Both log lines name the constraint; the response body never does.
  - Two beans mapping the same constraint name fail startup.
  - Transient, recoverable or resource-failure `DataAccessException` → 503 `SERVICE_UNAVAILABLE`. Any other `DataAccessException` → 500 `INTERNAL_ERROR`. Both logged at ERROR with the stack trace.
  - `ConstraintErrorMapping` lives in `common.error` and has no Spring imports.
- **`jakarta.validation.ConstraintViolationException`** (Bean Validation, not Hibernate's database `ConstraintViolationException`, which arrives wrapped in `DataIntegrityViolationException`):
  - 400 `VALIDATION_FAILED` only when the violation set is non-empty and every violation's root bean is a `@Controller`/`@RestController`.
  - Otherwise, including an empty set, it is a 500 `INTERNAL_ERROR` that echoes no field or value.
- **Base classes:** `AuditableEntity` and `SoftDeletableEntity` exactly as in data-and-migrations.md §4. Audit time comes from the `Clock`.
- **Soft delete:** `deleted_at` comes from the `Clock` and `deleted_by` from `CurrentActor`. The native restore acts only on a row that is currently deleted (`WHERE id = :id AND deleted_at IS NOT NULL`) and returns whether it changed one. A missing or active row is left untouched. A restore clears `deleted_*`, sets `updated_at` (`Clock`) and `updated_by` (`CurrentActor`), and bumps `version` by 1.
- **No `delete*` in Spring Data:** a new ArchUnit rule forbids methods named `delete*` on interfaces extending a Spring Data `Repository` (data-and-migrations.md §5). It gets a violating fixture and a compliant one.
- **Who audits a write:** the open `SystemActor` scope, otherwise `anonymous`. Story 7 inserts the token's `user:<sub>` between them.
  - A scope name is `system:<name>` or `user:<sub>`, the latter for async work acting on a user's behalf (data-and-migrations.md §4).
  - Scopes nest, and the outer one is restored on exit.
- **`IdGenerator`:** IDs from one instance strictly increase, also within one millisecond and when the clock steps back. It follows RFC 9562 §6.2 method 1:
  - a 12-bit counter in `rand_a`, seeded at each new millisecond with a random 11-bit value (top bit 0, leaving headroom);
  - it borrows the next millisecond on overflow;
  - it never moves its timestamp backwards.
- **Test database:** every `@SpringBootTest` gets the database from one shared test configuration. It uses one container per JVM, the same image digest as `infrastructure/compose.yaml`, and the real roles script: the app user is `seed_app` and Flyway runs as `seed_owner`.
  - The shared `seed_db` only ever receives the main migrations.
  - A test that changes migration history, or adds fixture schemas or migrations, gets its own throwaway database in the same container (owned by `seed_owner`, `CONNECT` granted to `seed_app`). This covers fresh-migrate, broken-location, failed-row and never-migrated readiness, and the persistence fixtures. Only the DB-stop case needs its own container.

**Never:**
- Idempotency (6c, already in deferred-work.md).
- The `@Transactional`-must-not-call-`@External` ArchUnit rule (the storage story, where `@External` first exists).
- "Audit fields cannot be set through a request DTO" enforcement (story 8, with the first DTOs).
- `user:<sub>` auditing (story 7).
- The Modulith `event_publication` table (story 9).
- No domain tables except test-only fixtures.
- No H2 or other in-memory database.
- No global soft-delete filter and no `@SoftDelete`.
- No passwords committed outside test code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh migrate | a throwaway empty database | `common` schema and history table exist, owned by `seed_owner`; `flyway validate` passes | N/A |
| Roles | connected as `seed_app` | `CREATE TABLE` in `common` and `public`, and `ALTER`/`DROP` of an owner-created table, are denied; SELECT/INSERT/UPDATE/DELETE on owner-created tables in `common` and in another owner-created schema work | N/A |
| Migrate profile | `--spring.profiles.active=migrate` against the test DB; then a broken migration location | migrations applied, context closed, no web server (exit code 0); the broken run fails startup with a Flyway error (exit code 1) | N/A |
| Readiness | schema current / one extra pending migration on the classpath / a failed migration row in the history / a database the `migrate` run never touched / DB stopped after startup | readiness 200 UP / 503 DOWN naming the version, in each of the next four cases; liveness 200 in all five | N/A |
| Auditing | create in `SystemActor.run("system:test", …)`; create outside any scope; update, outside any scope, after the clock moves 1 min | `created_by`/`updated_by` `system:test` / `anonymous`; `created_at` = `updated_at` = clock on insert; update keeps `created_*`, sets `updated_by` `anonymous`, `updated_at` +1 min, `version` 0 → 1 | N/A |
| Actor scopes | `SystemActor.run("admin", …)`; `run("system:outer", () -> run("user:42", …))`, then reading inside and after the inner scope | `IllegalArgumentException`; `user:42` inside, `system:outer` after the inner scope exits, `anonymous` after both | N/A |
| Soft delete | `markDeleted` inside `SystemActor.run("system:test", …)`, then lookups; native `restore(id)` after the clock moves 1 min; a new row reuses the deleted row's unique name | `deleted_at` = clock, `deleted_by` `system:test`; deleted row absent from JPA reads; after restore it is visible with `deleted_*` null, `updated_at` +1 min, `updated_by` set, `version` +1; reuse allowed (partial unique index); `restore` of an active or missing id returns false and changes nothing; restoring the original after its name was reused → 409 with the mapped code | N/A |
| Lock conflict | save with a stale `version` | 409 `CONCURRENT_MODIFICATION` | INFO |
| Integrity | duplicate on a mapped unique constraint; violation of an unmapped check constraint | 409 with the mapped code; 500 `DATA_INTEGRITY_VIOLATION`; no SQL or constraint text in the body | WARN / ERROR + stack, each naming the constraint |
| ArchUnit | a Spring Data repository fixture that declares `deleteById` | the no-`delete*` rule fails on it and passes on the compliant fixture | N/A |
| DB failure | `CannotGetJdbcConnectionException` / another `DataAccessException` | 503 `SERVICE_UNAVAILABLE` / 500 `INTERNAL_ERROR` | ERROR + stack |
| Controller validation | `@Validated` controller with a bad `@Min` request param (`ConstraintViolationException` rooted in the controller) | 400 `VALIDATION_FAILED` with one `errors[]` entry | DEBUG |
| Internal validation | persisting an entity that breaks a Bean Validation constraint; a `ConstraintViolationException` with no violations | 500 `INTERNAL_ERROR`; no field path or value in the body | ERROR |
| IDs | 10 000 calls in one fixed millisecond; clock stepped back 5 ms | every ID strictly greater than the previous one (unsigned, as text); version 7, variant 2 | N/A |
| Metrics | Prometheus after startup | contains `hikaricp_connections` | N/A |

</frozen-after-approval>

## Code Map

- `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml` -- every version is in the catalog; `failOnVersionConflict()`; Test tasks strip `SPRING_PROFILES_ACTIVE`/`SEED_API_DOCS_ENABLED`; `integrationTest` and `localProfileTest`, plus `checkOpenApi` in `check`.
- `backend/src/main/resources/application{,-local,-dev}.yml` -- the readiness group is set up but has no `db` yet. Add datasource, JPA, Flyway and readiness keys here; new `application-migrate.yml`.
- `backend/src/main/java/com/company/seed/common/ids/IdGenerator.java` -- currently stateless random UUIDv7 (`newId()`); make it monotonic. Its test is `IdGeneratorTest`.
- `backend/src/main/java/com/company/seed/common/error/web/GlobalExceptionHandler.java` -- add the persistence handlers. The existing `handleConstraintViolation` (line ~75) gets the controller-root rule. Use `respond(...)`/`log(...)` and `SafeMessages`. `levelOf` covers seed exceptions only.
- `backend/src/main/java/com/company/seed/common/error/ErrorCode.java` -- `CONCURRENT_MODIFICATION`, `DATA_INTEGRITY_VIOLATION` and `SERVICE_UNAVAILABLE` already exist.
- `backend/src/main/java/com/company/seed/config/ClockConfig.java` -- the UTC `Clock` bean; tests override it with a `@Primary` mutable clock.
- `backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java` -- `COMMON_ERROR_IS_FRAMEWORK_FREE` must still pass with `ConstraintErrorMapping`.
- Every `@SpringBootTest`: `SeedApplicationTests`, `EnvelopeContractIT`, `CorrelationIdIT`, `LocalReadableLogIT`, `ActuatorIT`, `ApiDocsDisabledIT`, `DevApiDocsIT` and `OpenApiExportIT`. Each must import the shared test DB configuration. `GlobalExceptionHandlerTest` (`@WebMvcTest`) must stay database-free.
- `infrastructure/postgres/init/01-roles.sh`, `infrastructure/compose.yaml` (postgres image and digest) -- reused by the test container. Read-only.
- `.env.example` -- already lists `DB_*` and `DB_OWNER_*`.

## Things this relies on

- `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql` (Flyway 12.4.0), `org.postgresql:postgresql` 42.7.14, Hibernate 7.4.13 -- Boot 4.1.2 BOM
- `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql` (2.0.5, class `org.testcontainers.postgresql.PostgreSQLContainer`) -- Boot 4.1.2 BOM / testcontainers-bom 2.0.5
- `DynamicPropertyRegistrar` -- Spring Framework 7 `spring-test`
- `spring.flyway.{enabled,user,password,locations,schemas,default-schema,table,placeholders,clean-disabled,create-schemas}` -- spring-boot-flyway 4.1.2 metadata
- `DataSourceHealthIndicator` (id `db`), `DataSourcePoolMetricsAutoConfiguration` (Hikari binder) -- spring-boot-jdbc 4.1.2
- `@EnableJpaAuditing(auditorAwareRef, dateTimeProviderRef)`, `AuditorAware`, `DateTimeProvider`, `AuditingEntityListener` -- Spring Data JPA (Boot BOM)
- `@SQLRestriction`, `org.hibernate.exception.ConstraintViolationException#getConstraintName` -- Hibernate 7
- `Flyway#info().pending()` -- Flyway 12
- `ObjectOptimisticLockingFailureException`, `DataIntegrityViolationException`, `TransientDataAccessException`, `RecoverableDataAccessException`, `DataAccessResourceFailureException`, `CannotGetJdbcConnectionException` -- Spring Framework 7
- Docker available to the build (Docker 29.9.2 locally; CI is story 10)
- `spring.flyway.{validate-on-migrate,out-of-order}` -- spring-boot-flyway 4.1.2 metadata
- `spring.jpa.open-in-view`, `spring.jpa.hibernate.ddl-auto`, `spring.jpa.properties.hibernate.{jdbc.time_zone,jdbc.batch_size,order_inserts}` -- Boot JPA / Hibernate 7
- `spring.datasource.hikari.{maximum-pool-size,minimum-idle,connection-timeout,max-lifetime,leak-detection-threshold}` -- spring-boot-jdbc 4.1.2 (HikariCP)
- JDBC URL `options=-c statement_timeout=15s` -- PostgreSQL JDBC 42.7
- `management.endpoint.health.group.{readiness,liveness}.include` -- spring-boot-health 4.1.2
- `spring.main.web-application-type` -- spring-boot 4.1.2; `-Duser.timezone=UTC` -- JVM
- Hibernate Validator and `ConstraintViolation#getRootBean` -- `spring-boot-starter-validation` (already a dependency)
- `micrometer-registry-prometheus` -- already a dependency (story 5)
- `docker.io/library/postgres:16.16-alpine3.24@sha256:0adbf25a…` and the `01-roles.sh` environment (`POSTGRES_USER`, `POSTGRES_DB`, `DB_NAME`, `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`) -- `infrastructure/compose.yaml`
- UUIDv7 monotonic counter -- RFC 9562 §6.2 method 1
- `HealthIndicator` / `Health` contributor API -- spring-boot-health 4.1.2
- Spring Data JPA native `@Query` with `@Modifying` -- Spring Data JPA (Boot BOM)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD` -- `.env.example`

## Tasks & Acceptance

**Execution:**
- [x] `libs.versions.toml`, `build.gradle.kts` -- add the JPA, Flyway, Flyway-PostgreSQL and PostgreSQL driver dependencies. Test-only: `spring-boot-testcontainers` and `testcontainers-postgresql`. Every Test task runs with `-Duser.timezone=UTC`, and so does `bootRun`.
- [x] `application.yml` -- datasource from `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` with the statement timeout; Hikari §9 values; JPA settings; Flyway keys with `enabled: false`, placeholder `app_role`, owner credentials from `DB_OWNER_*`; readiness group. `application-local.yml`: Flyway enabled. New `application-migrate.yml`: Flyway enabled, `spring.main.web-application-type: none`.
- [x] `src/main/resources/db/migration/common/V202611160900__common_grant_app_role.sql` -- the grants from Always (the schema is created by Flyway through `schemas`).
- [x] `config/MigrateRunner.java` -- `@Profile("migrate")` `ApplicationRunner` that closes the context once startup (and so the migration) has succeeded.
- [x] `config/SchemaVersionHealthIndicator.java` -- the `schemaVersion` contributor. Builds a read-only Flyway from the same locations, schemas and table on the app datasource; DOWN with the pending count when any are pending, UP otherwise.
- [x] `common/ids/IdGenerator.java` -- the monotonic algorithm from Always; extend `IdGeneratorTest`.
- [x] `common/actor/{CurrentActor,SystemActor,DefaultCurrentActor}.java`:
  - `CurrentActor` is the port: `String id()`.
  - `SystemActor` holds the thread-bound scope: `run(String, Runnable)` and `call(String, Supplier<T>)`. It rejects names that start with neither `system:` nor `user:`.
  - `DefaultCurrentActor` returns the scope or `anonymous`.
- [x] `common/auditing/{AuditableEntity,SoftDeletableEntity,JpaAuditingConfig}.java` -- as in data-and-migrations.md §4. `auditorAware` delegates to `CurrentActor`; `auditingDateTimeProvider` uses the `Clock`.
- [x] `common/error/ConstraintErrorMapping.java` and `common/error/web/GlobalExceptionHandler.java`, `SafeMessages.java` -- the mappings from Always. Add a safe message for a mapped 409 ("Conflicts with existing data").
- [x] Test harness `fixtures/db/TestDatabase.java` -- a `@TestConfiguration` with a static `PostgreSQLContainer` (compose image and digest, `01-roles.sh` copied into `/docker-entrypoint-initdb.d` with its env) and a `DynamicPropertyRegistrar` (app user `seed_app`, Flyway enabled as `seed_owner`). Import it in every `@SpringBootTest` from the Code Map.
- [x] Persistence fixtures, test-only, under `com.company.fixtures` and `src/test/resources/db/migration/fixture`:
  - an `AuditedThing extends SoftDeletableEntity` with `@SQLRestriction`, `@NotBlank name`, a partial unique index and a check constraint;
  - a repository with a native `restore` that follows the soft-delete rule in Always;
  - a `ConstraintErrorMapping` (unique constraint → `THING_ALREADY_EXISTS`);
  - a probe controller for the error rows.
  - All of it is wired only by the persistence ITs, through their own test configuration (entity scan, repositories, extra Flyway location and schema).
- [x] Tests:
  - `MigrationIT`: fresh migrate, validate, migrate-profile rows;
  - `DatabaseRolesIT`;
  - `ReadinessIT`: its own container for the stop case;
  - `AuditingPersistenceIT`;
  - `SoftDeletePersistenceIT`;
  - `PersistenceErrorMappingIT`;
  - `IdGeneratorTest`;
  - `ActuatorIT`: the Hikari metric.
  - Every matrix row is covered.
- [x] `test/.../architecture/SeedArchitectureRules.java`, `ArchitectureRulesDetectViolationsTest` and fixtures -- the no-`delete*` rule, added to `ALL`, with a violating fixture and a compliant one.
- [x] `_snap-output/implementation-artifacts/deferred-work.md` -- one entry each for:
  - story 7: add `user:<sub>` to `CurrentActor`, between the system scope and `anonymous`;
  - story 9: the Modulith `event_publication` table in `common`;
  - the storage story: the `@External` ArchUnit rule;
  - story 8: the audit-fields-not-in-request-DTOs check.
- [x] `README.md` -- note that the build needs Docker, and that `bootRun` with `local` runs after `docker compose up`. For the `migrate` profile, say that it needs `DB_*` and `DB_OWNER_*`, and exits 0 on success and 1 on failure.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, and `checkOpenApi` passes with an unchanged `api.yaml`.
- Given the `local` profile and the compose stack, when the app starts, then Flyway migrates as `seed_owner` and the app runs as `seed_app` (manual check).
- Given the module and architecture rules, when `ModularityTest` and `ArchitectureTest` run, then they pass with `common.actor` and `common.auditing`.

## Implementation Notes

- Planning: two critic passes (15, then 14 findings), all resolved by spec edits the user approved. A third pass was skipped at the user's choice on 2026-11-16 ("apply your recommendation", which included proceeding without one).

- Implementation (subagent): all tasks done. Choices it made beyond the task list:
  - Flyway is enabled for tests in `src/test/resources/config/application.yml`, because a `DynamicPropertyRegistrar` runs after Flyway's auto-configuration condition is evaluated; the registrar still supplies `DB_*`.
  - The test container sets `max_connections=500`, because cached contexts each hold a fixed pool of 10.
  - Hikari durations are written as plain milliseconds.
  - `DB_PASSWORD`/`DB_OWNER_PASSWORD` have no defaults.
  - `SqlExceptionHelper` logging is off, so SQL errors are logged once, by the handler.
  - The lock handler maps the broader `OptimisticLockingFailureException`.
  - `restore` is a repository fragment using `EntityManager`, because it needs `Clock`/`CurrentActor` and a boolean result.
  - The fixture implements `Persistable` for assigned-UUID inserts; deferred entry for story 8.
  - `ReadinessIT` turns on `show-details=always` to assert the DOWN reasons.
  - The test-only `jakarta-persistence-api`/`spring-data-commons` catalog entries were removed (now on the main classpath).
- Not done: the manual `bootRun` with `local` against the compose stack (it changes the developer's Docker state); `LocalReadableLogIT` runs the `local` profile against the test container.
- Verify (main agent): `./gradlew clean build` passes with 270 tests in 29 suites and 0 failures (31 s); `api.yaml` is unchanged; snap-guard reports 0 findings across 59 files; every I/O matrix row maps to a named passing test.

- Review pass 1 patches were applied by the implementation subagent (re-engaged). Two surprises its new tests caught:
  - Hibernate 7 logs SQL errors under `org.hibernate.orm.jdbc.error`, not `SqlExceptionHelper`, so the earlier silencing never took effect. That category is now off, and an all-logger assertion guards it.
  - With UP cached, the DB-stopped readiness case shows `db` DOWN while `schemaVersion` stays UP; readiness is still 503.
  - The subagent also updated the migration file name in this spec's task line after the rename to `V202611160900__common_grant_app_role.sql`. Any local database that applied the old name must be recreated.
- Final verification (main agent): `./gradlew clean build` passes with 293 tests in 32 suites and 0 failures (33 s). The build also passes with `SPRING_PROFILES_ACTIVE=local` and `SEED_API_DOCS_ENABLED=true` exported. `api.yaml` is unchanged. snap-guard: 0 findings across 66 files. Doubt scan: clean. Context lake: none (write-back skipped).

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 51 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | security + verif-gap | Unmapped integrity and other `DataAccessException` ERROR logs carry PostgreSQL `Detail:` row values; the `SqlExceptionHelper: off` override is unverified | medium | The stack trace includes Hibernate's message with the server detail (`Failing row contains (...)`, `Key (name)=(...)`); PgJDBC's `logServerErrorDetail` defaults to true; `PersistenceErrorMappingIT` only inspects handler lines. | patch (P2, P3) |
| R2 | edge | DB down at transaction start raises `CannotCreateTransactionException` (not a `DataAccessException`) and gets 500 | medium | `JpaTransactionManager.doBegin` wraps connection failures in `CannotCreateTransactionException` (a `TransactionException`); no handler maps it, so it falls to `handleUnexpected`. | patch (P1) |
| R3 | blind + edge | Flyway inherits the app URL's `statement_timeout=15s`, so long migrations fail the `migrate` run | medium | With only `spring.flyway.user/password` set, Boot builds Flyway's connection from `spring.datasource.url`, options included. | patch (P2) |
| R4 | edge | `migrate` with Flyway disabled by an override exits 0 without migrating | medium | `MigrateRunner` closes the context unconditionally; a shared `SPRING_FLYWAY_ENABLED=false` env would yield a silent no-op deploy step. | patch (P6) |
| R5 | security + blind + slop | Readiness runs a full Flyway classpath scan and history query on every probe; settings bound key by key | medium | `health()` calls `flyway.info()` every time on an unauthenticated port; `FlywayProperties` exists for the binding. Once UP, this image's migrations cannot become un-applied. | patch (P4) |
| R6 | verif-gap + blind | `schemaVersion` IGNORED branch untested; timestamped versions from parallel branches can stall deploys with no guidance | medium | No test produces an IGNORED migration; README has no re-stamp guidance. | patch (P4, P16) |
| R7 | verif-gap | `spring.flyway.enabled: false` default (and `local: true`) never verified | medium | The test `config/application.yml` forces `true` everywhere; deleting either main line keeps every test green. | patch (P13) |
| R8 | verif-gap | `RecoverableDataAccessException` → 503 untested | medium | Pre-verified: no probe or row covers it. | patch (P1) |
| R9 | security | No `sslmode` configurable on the JDBC URL | low | URL has no `sslmode`; PgJDBC default `prefer` does not verify. A direct, env-driven parameter fixes it. | patch (P2) |
| R10 | security | Owner (DDL) credentials referenced by the base config every profile loads | low | `spring.flyway.user/password` live in `application.yml`, read only when Flyway runs; moving them to `local`/`migrate` (and tests) is direct. | patch (P2) |
| R11 | security | App role has `DELETE` on every owner table | false | Specified by data-and-migrations.md §10 and needed for 6c's retention cleanup; soft delete is an application rule. | rejected |
| R12 | security + edge | `SystemActor` accepts whitespace or control characters inside names | low | `requireValid` checks only prefix and outer whitespace; `system:a\nb` passes into audit columns and logs. | patch (P7) |
| R13 | security | Requests audited as `anonymous` | false | Approved by intent (option a); story 7 entry exists. | rejected |
| R14 | security | Flyway placeholders unescaped in quoted identifiers | low | Values come from deployment configuration, not users; escaping adds complexity. | rejected |
| R15 | security | Mapped 409 reveals existence of a value | false | By design (api-standards.md §2 `<ENTITY>_ALREADY_EXISTS`). | rejected |
| R16 | security | UUIDv7 reveals creation time and order | false | Chosen in decisions.md; 62 random bits remain. | rejected |
| R17 | security | Test-only hard-coded passwords | false | Confined to `src/test` and Testcontainers. | rejected |
| R18 | blind | `migrate` starts the whole app; `MigrateRunner` has no order | low | No other runners or schedulers exist yet; ordering first is direct; scheduler shutdown in `migrate` belongs with story 9's jobs. | patch (P6) + defer (D1) |
| R19 | blind + edge | No-`delete*` rule misses `remove*` and `@Modifying @Query("DELETE …")` | low | The rule matches the `delete` prefix only; extending it is direct. 6c's cleanup will need an exemption. | patch (P11) + defer (D2) |
| R20 | blind | Constraint lookup ignores the schema | low | Collisions need two schemas with the same table and constraint names; the convention names include the table. Documenting the uniqueness requirement is direct. | patch (P12) |
| R21 | blind | Transient lock/serialization failures map to 503, no `Retry-After` | low | As specified by errors-and-logging.md §2 ("503 if transient"); remapping changes intent. | rejected |
| R22 | blind + verif-gap + edge | `SoftDeletableEntity.restore()` unusable on rows hidden by `@SQLRestriction`; `markDeleted` accepts nulls; fixture lacks a `deleted_*` consistency check | low | `restore()` is mandated by §4; documenting when it is valid, null guards and a CHECK in the fixture are direct. | patch (P8) |
| R23 | blind | Native restore leaves managed instances stale | medium | `executeUpdate` without flush/clear; a managed instance in the same transaction keeps the old `version` and fails or writes stale audit values. This is the reference pattern story 8 copies. | patch (P9) |
| R24 | blind | Audit `Instant`s keep nanoseconds while `timestamptz` stores microseconds | low | On Linux `Instant.now` has sub-microsecond precision, so saved and reloaded values differ; truncation is direct. | patch (P10) |
| R25 | blind | `SqlExceptionHelper: off` hides SQL errors in non-web callers | low | No non-web DB callers exist yet; jobs and listeners arrive in story 9. | defer (D1) |
| R26 | blind | `-Duser.timezone=UTC` only on tests and `bootRun` | low | The container image (quality-and-delivery.md §6, story 10) sets it; `hibernate.jdbc.time_zone=UTC` covers JDBC. | rejected |
| R27 | blind | Migration names break the `<module>_<verb>_<object>` convention | low | `common_initial_schema` has no verb; it has never reached a shared environment, so renaming is safe and direct. | patch (P5) |
| R28 | blind | `ReadinessIT.DatabaseStopped` leaves a broken context cached | low | No `@DirtiesContext`; its pool keeps retrying and can pollute captured output. | patch (P14) |
| R29 | blind | Untested: schema-qualified / case-insensitive lookup, null constraint → `unknown`, concurrent `IdGenerator`, overflow drift, async actor scope | low | Lookup and null cases get a direct unit test; concurrency and drift tests add complexity for unlikely cases; async scope belongs with story 9's `TaskDecorator`. | patch (P1) + defer (D1) |
| R30 | verif-gap + edge | `constraintName` reads only Hibernate's exception; a `JdbcTemplate` path gives 500 for a mapped constraint | low | No `JdbcTemplate` use exists; a PgJDBC dependency in main code would be needed (driver is `runtimeOnly`). Documenting the scope is direct. | patch (P12) |
| R31 | edge | A cause chain cycle longer than one loops forever in `constraintName` | low | The self-cause check only stops one-step cycles; an identity set is a direct fix. | patch (P1) |
| R32 | edge | Grant hard-codes `flyway_schema_history` although the table name is configurable | low | `${flyway:table}` is a direct substitute. | patch (P5) |
| R33 | edge | Readiness UP when an applied migration's checksum differs | low | The `migrate` run's `validate-on-migrate` fails the deployment first; a validate per probe adds cost. | rejected |
| R34 | edge | `CurrentActor.id()` returning null gives a bare NPE | low | The interface documents "never null"; only one implementation exists. | rejected |
| R35 | edge | `TestDatabase` resolves `infrastructure/` relative to `backend/` only | low | An IDE run from the repository root fails every `@SpringBootTest`; trying both paths is direct. | patch (P15) |
| R36 | edge | `EcsLogLines` throws on a partial JSON line | low | Interleaved output can start a line with `{`; skipping unparsable lines is direct. | patch (P15) |
| R37 | edge | `TestDatabase` registrar does not itself enable Flyway as the spec task says | low | Deviation documented in Implementation Notes; the fix would edit the spec. A Javadoc note is direct. | patch (P15) |
| R38 | slop | `handleDataIntegrity` repeats `respond` inline | low | A `detail` parameter on `respond` removes both copies. | patch (P1) |
| R39 | slop | `TestDatabase.migrate` copies `spring.flyway.*` literals | low | Drift would fail tests visibly; deriving them adds plumbing. | rejected |
| R40 | slop | Unrelated second sentence in the `user.timezone` comment; Javadocs that restate names | low | Direct deletions. | patch (P12) |

## Design Notes

**Why one shared test DB configuration:** every `@SpringBootTest` now needs a datasource. One static container per JVM, with the real roles script, keeps the role separation honest in every test and matches the per-JVM reuse in quality-and-delivery.md §1.

**Why the `schemaVersion` check reads Flyway as the app user:** in shared environments the app has Flyway disabled and must not hold DDL rights, but it still has to report DOWN when the `migrate` run has not happened yet.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `api.yaml` unchanged.

**Manual checks (if no CLI):**
- `docker compose -f infrastructure/compose.yaml --env-file .env up -d postgres`, then `./gradlew bootRun --args='--spring.profiles.active=local'` -- the app starts, and `curl :8081/actuator/health/readiness` returns UP.

## Tech Spec Critic Findings

- [SOLVED] [medium] check-5 | spec line 80 | "empty test DB" for Fresh migrate conflicts with "one container per JVM": if MigrationIT uses the shared container, "fresh" is never true, and the broken-location run, the injected failed history row and the fixture migrations/schema all mutate the history every other `@SpringBootTest` validates and reports readiness against.
- [SOLVED] [medium] check-3 | spec line 83 | The Readiness row has no case for a database the `migrate` run never touched (no `common` schema/history table, app user without `USAGE`), the very scenario the Design Notes give as the reason for the check.
- [SOLVED] [medium] check-1 | spec line 50 | The unmapped integrity violation is a 500 `DATA_INTEGRITY_VIOLATION` logged at WARN, contradicting CAP-4's "Every 5xx is logged once at ERROR with its correlation ID" without naming or justifying the departure.
- [SOLVED] [medium] check-5 | spec line 57 | The native restore does not state what happens for a row that is not deleted or does not exist; updating any row with that id would bump an active row's `version`/`updated_*` and cause spurious 409s.
- [SOLVED] [medium] check-1 | spec line 31 | data-and-migrations.md §3–§7 are declared applicable, but §7's ArchUnit rule (`@Transactional` classes must not call `@External` ports) and §5's "the Spring Data interface declares no `delete*`" have no task, matrix row or Never entry, so their scope is undetermined.
- [SOLVED] [low] check-1 | spec line 19 | CAP-6 "Audit fields cannot be set through any request DTO" is neither covered nor listed under Never/deferred.
- [SOLVED] [low] check-5 | spec line 46 | "DOWN when any classpath migration is pending" could mean Flyway's PENDING state only; a classpath migration older than the latest applied one is IGNORED (with `out-of-order: false`), so readiness could stay UP over a missing migration.
- [SOLVED] [low] check-2 | spec line 50 | The WARN log for an unmapped `DataIntegrityViolationException` is not stated to include the constraint name, so the operator cannot tell which constraint needs a `ConstraintErrorMapping`.
- [SOLVED] [low] check-2 | spec line 139 | The `schemaVersion` DOWN detail carries only a pending count, so in the failed-migration case the operator is not told which migration failed.
- [SOLVED] [low] check-5 | spec line 50 | It is not stated which code wins when two `ConstraintErrorMapping` beans map the same constraint name.
- [SOLVED] [low] check-3 | spec line 85 | The Soft delete row does not give the outcome of restoring a row after a new row has reused its unique name.
- [SOLVED] [low] check-3 | spec line 84 | The Auditing row's update case does not say which actor scope the update runs in or what `updated_by` becomes.
- [SOLVED] [low] check-3 | spec line 84 | No matrix row covers `SystemActor` rejecting a name without `system:`/`user:`, or an inner scope restoring the outer one on exit.
- [SOLVED] [low] check-6 | spec line 109 | `## Things this relies on` omits the `HealthIndicator`/`Health` contributor API, Spring Data native `@Query`/`@Modifying`, and the app-side `DB_HOST`/`DB_PORT` variables.
