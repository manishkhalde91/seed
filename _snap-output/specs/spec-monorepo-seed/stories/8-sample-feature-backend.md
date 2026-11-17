---
title: 'Story 8 — Sample feature backend (DEMO ONLY)'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_commit: '3facb9d01f2268fe26d09a8b953fe0eb3752b0fc'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/backend-architecture.md'
  - '{project-root}/_snap-output/specs/spec-monorepo-seed/api-standards.md'
rules_touched: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** No feature module exists, so the foundation has never been exercised end to end: envelope, validation, auditing, soft delete, optimistic locking, RBAC, paging and the Repository Pattern. Developers have no pattern to copy (CAP-20 backend part).

**Approach:**
- **The sample module:** `com.company.seed.feature.sample`, every file marked DEMO ONLY, provides CRUD, soft delete, ADMIN-only restore and paged, sorted listing through the full Repository Pattern.
- **Shared pieces it needs:**
  - `common.api.PageQuery` with whitelisted sorting;
  - the api-standards.md §7 `OperationCustomizer`;
  - an ArchUnit rule keeping audit fields out of request DTOs;
  - `docs/validation-rules.md`;
  - `GET /api/v1/me`.
- **Decisions (2026-11-16):**
  - `/me` is built here (option a);
  - `AuditableEntity.version` becomes a `Long`, so Spring Data's null-version rule marks new entities (option a);
  - the list has paging and sorting only, no filters (option a).
- The export endpoint (`POST …/export`, 202) belongs to story 9.

## Boundaries & Constraints

**Always:**
- **Endpoints** (`/api/v1/sample-records`, all `@PreAuthorize` with `Roles`), each returning the envelope through `ApiResponses`:
  - `GET` (USER): paged and sorted.
  - `GET /{id}` (USER).
  - `POST` (USER): 201 with `Location`.
  - `PUT /{id}` (USER): `name`, `description`, `version`. A full replace: an omitted or null `description` clears it.
  - `DELETE /{id}` (ADMIN): soft delete, 200 `deleted()`.
  - `POST /{id}/restore` (ADMIN): 200 with the record.
  - Messages: "Sample record created", "Sample record retrieved", "Records retrieved", "Sample record updated", "Sample record deleted", "Sample record restored"; `/me` uses "Current user".
- **Requests:**
  - `CreateSampleRecordRequest(@NotBlank @Size(max=100) name, @Size(max=1000) description)`.
  - `UpdateSampleRecordRequest` adds `@NotNull @PositiveOrZero Long version`.
  - Neither carries any audit field.
  - `SampleRecordResponse(id, name, description, version, createdAt, createdBy, updatedAt, updatedBy)`.
- **Errors:**
  - Missing or soft-deleted id → 404 `SAMPLE_RECORD_NOT_FOUND`.
  - `version` differing from the stored one → 409 `CONCURRENT_MODIFICATION`, checked in the service and also enforced by `@Version`.
  - Duplicate active name (on create, on update, and on restoring a record whose name an active record now holds) → 409 `SAMPLE_RECORD_ALREADY_EXISTS` via a `ConstraintErrorMapping` for `uq_sample_record__name`.
  - Two concurrent updates with the same current `version`: the loser gets 409 `CONCURRENT_MODIFICATION` (the `@Version` race), never 500.
  - Restoring an active record → 409 `INVALID_STATE_TRANSITION`; restoring a missing one → 404.
  - These are `SeedException` subclasses declared in `feature.sample.domain`, which stays framework-free.
- **Paging** (`common.api.PageQuery`):
  - `page` ≥ 0 (default 0); `size` 1–100 (default 20); repeatable `sort=field[,asc|desc]`, where the direction defaults to `asc`.
  - Each endpoint passes its whitelist (sample: `name`, `createdAt`; default `createdAt,desc`).
  - An unknown field, a bad direction, a negative or non-numeric `page`, or a non-numeric or out-of-range `size` → 400 `VALIDATION_FAILED` with an `errors[]` entry on `page`, `size` or `sort`.
  - A page past the last one is 200 with an empty list and correct `pagination`.
  - Soft-deleted records are absent from the list and from `totalElements`.
  - Services return `PageResult<T>`; Spring Data `Page` stays inside the repository implementation.
- **Repository Pattern** (backend-architecture.md §4.5, §5):
  - `domain/SampleRecord` and `SampleRecordRepository` are plain Java: `save`, `findById`, `findPage`, `restore`.
  - `infrastructure/persistence/` holds `JpaSampleRecordRepository`, `SampleRecordJpaEntity` (extends `SoftDeletableEntity`, `@SQLRestriction("deleted_at IS NULL")`), `SampleRecordSpringDataRepository` (extends `Repository`, no `delete*`) and `SampleRecordPersistenceMapper`.
  - `application/SampleRecordService` is `@Transactional`, `readOnly` for queries.
  - IDs come from `IdGenerator`; soft delete uses `markDeleted(clock.instant(), currentActor.id())`; restore is the native update from story 6.
- **Schema:** `db/migration/sample/V202611170900__sample_create_sample_record.sql`, as in data-and-migrations.md §11 (check, partial unique index, `created_at` index), plus `GRANT USAGE ON SCHEMA sample TO ${app_role}`. `spring.flyway.locations` and `schemas` gain `sample`.
- **New entities:** `AuditableEntity.version` becomes `Long` (null before the first insert). The story 6 fixture drops `Persistable`, and data-and-migrations.md §4 shows `Long`.
- **OpenAPI** (`OperationCustomizer`):
  - every 2xx response schema is wrapped as `ApiResponse<T>` (paged lists include `pagination`);
  - every operation lists `ApiErrorResponse` for 400, 401, 403, 404, 409, 422 and 500;
  - operations declare the optional `X-Correlation-Id` request header and response header;
  - the envelope schemas get `required` lists;
  - `api.yaml` is regenerated.
- **ArchUnit:** a new rule says records named `*Request` under `..api..` have no component named `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `deletedAt` or `deletedBy`. It gets a violating fixture and a compliant one.
- **`GET /api/v1/me`** (any authenticated caller): `{subject, name, roles}`.
  - `name` is the token's `name` claim, else `preferred_username`, else `subject`.
  - `roles` are the caller's effective roles, including those implied through the role hierarchy (an ADMIN gets `["ADMIN","USER"]`), sorted.
  - It lives in the `security` module and is enveloped.
- **Docs:** `docs/validation-rules.md` lists field → constraint → frontend validator for both sample requests.
- **DEMO ONLY:** every `feature/sample` file and the sample migration carry the marker.
- **Removal:** delete `feature/sample` in main and test sources, `db/migration/sample`, the `sample` Flyway location and schema, and the sample rows of `docs/validation-rules.md`, then run `./gradlew updateOpenApi`. That must leave a passing build.
  - The procedure is written in the README.
  - Every sample test, including its OpenAPI assertion, lives in the sample's own test package, never in a shared test.
  - The implementer runs the procedure once on a scratch copy and records the result (a documented manual check, not a build task).

**Never:**
- Export, operations or storage (stories 9 and 10).
- Filters on the list.
- PATCH.
- Hard delete.
- Spring Data types or JPA entities outside `infrastructure/persistence`.
- Business rules in the controller.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create | USER `POST {name:"Example", description:"x"}` | 201, `Location: /api/v1/sample-records/{uuidv7}`, data with `version 0`, `createdBy user:<sub>` | N/A |
| Validation | blank name; 101-char name; 1001-char description; missing `version` on PUT | 400 `VALIDATION_FAILED`, `errors[].field` = `name`/`description`/`version`, `code` = constraint name | N/A |
| Duplicate | second create with an active record's name; same name after that record is soft-deleted | 409 `SAMPLE_RECORD_ALREADY_EXISTS`; 201 (partial index) | N/A |
| Read | existing id; unknown id; soft-deleted id | 200; 404 `SAMPLE_RECORD_NOT_FOUND`; 404 | N/A |
| Update | correct `version`, `description` omitted; stale `version`; two concurrent updates with the same `version`; `name` changed to another active record's name | 200 with `version+1`, `updatedAt` moved, `createdBy` unchanged, `description` null; 409 `CONCURRENT_MODIFICATION`; one 200 and one 409 `CONCURRENT_MODIFICATION`; 409 `SAMPLE_RECORD_ALREADY_EXISTS` | N/A |
| List | 25 records, `?page=1&size=10&sort=name,asc`; `sort=name` (no direction); `page=5&size=10` | 10 items sorted by name, `pagination {page:1,size:10,totalElements:25,totalPages:3,hasNext:true}`; ascending; 200 with `data:[]` and `pagination {page:5,…,totalElements:25,hasNext:false}` | N/A |
| Bad paging | `sort=description`, `sort=name,up`, `size=101`, `page=-1`, `size=abc` | 400 `VALIDATION_FAILED` naming `sort`/`size`/`page` | N/A |
| Delete / restore | ADMIN delete, then GET and list, then restore, then GET and list; USER delete or restore | 200 `data:null`; 404 and absent from the list (`totalElements` −1); 200 with the record (`version` +1, `updatedAt` moved, `updatedBy` the admin); 200 and back in the list; USER gets 403 on both | WARN on 403 |
| Restore states | restore an active record; restore an unknown id; restore a deleted record whose name an active record now holds | 409 `INVALID_STATE_TRANSITION`; 404; 409 `SAMPLE_RECORD_ALREADY_EXISTS` | N/A |
| No token | any sample endpoint | 401 | N/A |
| `/me` | USER token `sub=u-1`, `name=Ann`, `roles=[USER]`; ADMIN token without `name` but with `preferred_username=root` | `{subject:"u-1", name:"Ann", roles:["USER"]}`; `{…, name:"root", roles:["ADMIN","USER"]}` | N/A |
| OpenAPI | regenerated `api.yaml` | `POST /api/v1/sample-records` 201 schema is `ApiResponse` wrapping `SampleRecordResponse`; the list's 200 includes `pagination`; each operation lists 400/401/403/404/409/422/500 with `ApiErrorResponse` and the `X-Correlation-Id` header | N/A |
| ArchUnit | a fixture `*Request` record with `createdBy` | the new rule fails on it; the compliant fixture passes | N/A |
| Removability | the README removal procedure applied to a scratch copy | `./gradlew clean build` passes (recorded in Implementation Notes) | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/company/seed/common/auditing/AuditableEntity.java:41` -- `@Version private long version;` becomes `Long`; `getVersion()` changes type. `backend/src/test/java/com/company/fixtures/persistence/AuditedThing.java` implements `Persistable`: remove that.
- `backend/src/main/java/com/company/seed/common/api/{ApiResponses,PageResult,ApiResponse,PageMeta}.java` -- `page(message, PageResult)` exists; add `PageQuery` here.
- `backend/src/main/java/com/company/seed/common/error/{SeedException,ResourceNotFoundException,ConflictException,ErrorCode,ConstraintErrorMapping}.java` -- base classes for the sample exceptions and the mapping bean.
- `backend/src/main/java/com/company/seed/common/{ids/IdGenerator,actor/CurrentActor,auditing/SoftDeletableEntity}.java`, `config/ClockConfig` -- reuse.
- `backend/src/test/java/com/company/fixtures/persistence/*` (`AuditedThingRestoreImpl`) -- the native-restore reference (flush/clear, `Clock`, actor, version bump).
- `backend/src/main/resources/application.yml` (`spring.flyway.locations: classpath:db/migration/common`, `schemas: common`) -- add `sample`. `db/migration/common/V202611160900__common_grant_app_role.sql` has the default-privilege pattern.
- `backend/src/main/java/com/company/seed/config/OpenApiConfig.java` -- add the `OperationCustomizer`; `api.yaml` changes, so run `./gradlew updateOpenApi`.
- `backend/src/main/java/com/company/seed/security/{Roles,JwtRoleConverter,SecurityContextActor}.java` -- `/me` reads the JWT; roles from the converter's authorities.
- `backend/src/test/java/com/company/seed/architecture/SeedArchitectureRules.java` -- add the audit-field rule; the Repository-Pattern, envelope and `@PreAuthorize` rules already apply to `feature/sample`.
- `backend/src/test/java/com/company/fixtures/{security/TestJwt,web/LenientRestClient,db/TestDatabase}` -- test helpers.

## Things this relies on

- Spring Data JPA `Repository`, `Pageable`/`PageRequest`/`Sort`, null-version newness (`JpaMetamodelEntityInformation#isNew`) -- Spring Data JPA (Boot 4.1.2 BOM)
- Hibernate `@SQLRestriction`, `@Version` with `Long` -- Hibernate 7.4
- springdoc `OperationCustomizer`, swagger-core `Schema`/`ApiResponse`/`Parameter`/`Header` models -- springdoc 3.2.0 / swagger-core 2.2.55
- Spring Modulith `@ApplicationModuleTest` -- spring-modulith-starter-test 2.1.2
- Jakarta Bean Validation constraints (`@NotBlank`, `@Size`, `@NotNull`, `@PositiveOrZero`) -- Hibernate Validator 9.1
- Flyway 12, Testcontainers 2 + Docker, ArchUnit 1.4.2, Spring Security JWT (`Jwt#getClaimAsString`) -- already in the build
- PostgreSQL 16 partial unique indexes and `GRANT`, the Flyway `${app_role}` placeholder -- story 6
- Spring MVC `HandlerMethodArgumentResolver` (the `PageQuery` binding) -- Spring Framework 7
- Spring Security method security (`@PreAuthorize`) and `RoleHierarchy#getReachableGrantedAuthorities` -- Spring Security 7.1.2

## Tasks & Acceptance

**Execution:**
- [x] `common/auditing/AuditableEntity.java` (+ `AuditedThing` fixture, data-and-migrations.md §4) -- `Long version`.
- [x] `common/api/PageQuery.java` (+ a binding helper so controllers take it as a parameter) -- the paging rules from Always.
- [x] `db/migration/sample/V202611170900__sample_create_sample_record.sql`, `application.yml` Flyway locations and schemas.
- [x] `feature/sample/**`: `package-info.java` (DEMO ONLY), `api/` (controller, requests, response, mapper), `application/SampleRecordService`, `domain/` (aggregate, repository, exceptions), `infrastructure/persistence/` (Jpa repository, entity, Spring Data interface, mapper), `config/SampleConstraintErrors` (the `ConstraintErrorMapping` bean).
- [x] `security/api/MeController.java` (+ response record) -- `/api/v1/me`.
- [x] `config/OpenApiConfig.java` -- the `OperationCustomizer`; regenerate `backend/openapi/api.yaml`.
- [x] `SeedArchitectureRules` + fixtures -- the audit-field rule.
- [x] `docs/validation-rules.md`.
- [x] Tests:
  - `SampleRecordApiIT` (every matrix row over HTTP with `TestJwt`);
  - `SampleRecordPersistenceIT`;
  - `SampleRecordServiceTest` (mocked repository);
  - `SampleModuleTests` (`@ApplicationModuleTest`);
  - `PageQueryTest`;
  - `MeApiIT`;
  - an OpenAPI assertion in a sample-package test;
  - the removability check (manual, recorded).
- [x] `README.md` -- a short "the sample module" section: what it shows and how to delete it.

**Acceptance Criteria:**
- Given Docker is running, when `./gradlew clean build` runs, then it passes, with the regenerated `api.yaml` committed and the module and architecture rules green.

## Implementation Notes

- Planning: one critic pass (14 findings), all resolved by spec edits the user approved. The user's "apply your recommendation" included proceeding without a second pass (2026-11-16).
- Removability check (2026-11-17, manual): the README "The sample module" procedure was applied to a scratch copy of the working tree (feature/sample main and test deleted, `db/migration/sample` deleted, the `sample` Flyway location and schema removed from `application.yml`, the sample section of `docs/validation-rules.md` deleted). `./gradlew updateOpenApi && ./gradlew clean build` → BUILD SUCCESSFUL; the regenerated `api.yaml` keeps only `/api/v1/me`. Shared tests no longer name `common` as the only module: `TestDatabase.mainLocations()/mainSchemas()` read them from `application.yml`, `PersistenceFixtures` appends the fixture migration with a `FlywayConfigurationCustomizer`, and `MigrationIT` compares applied versions with the main locations' versions.
- Shared tests changed because the sample is the first application controller and feature table: `GlobalExceptionHandlerTest`'s `@WebMvcTest` is limited to its probe controllers; `RouteTableIT` sends a UUID for path variables and `{}` to handlers with a body, and requires exactly 403 for every role-checked route: `security.PreAuthorizeHandlerInterceptor` evaluates a handler's `@PreAuthorize` before argument binding (review P1).
- `MeController` is `@ConditionalOnWebApplication(SERVLET)`, like `SecurityConfig` whose `RoleHierarchy` it reads, so the `migrate` profile and `webEnvironment = NONE` contexts still start.

- Main agent: the implementation subagent's first run stopped at an account usage limit before any edit and was resumed after the reset.
- Implementer-reported deviations and risks:
  - **Shared tests:** `TestDatabase` reads migration locations and schemas from `application.yml` (so the sample can be removed); `MigrationIT`, `ReadinessIT` and `PersistenceTest` use it; `GlobalExceptionHandlerTest` loads only its probe controllers.
  - **`RouteTableIT`:** it accepts 400 or 403 for role-less tokens on body-taking routes, because MVC validates the body before `@PreAuthorize`.
  - **`MeController`:** it loads only in servlet web apps.
  - **No-change PUT:** a PUT that changes nothing does not bump `version`/`updatedAt`.
  - **Validation rules doc:** the frontend validators in `docs/validation-rules.md` are marked planned.
- Verify (main agent): `./gradlew clean build` passes with 612 tests in 62 suites and 0 failures (49 s); `api.yaml` was regenerated; snap-guard reports 0 findings across 57 files.

- Review pass 1 patches P1–P11 were applied by the implementation subagent (re-engaged; one attempt stopped on a network error before any edit and was resumed):
  - **Security:** `PreAuthorizeHandlerInterceptor` evaluates `@PreAuthorize` before argument binding, so a role-less caller gets 403 with no `errors[]`; `RouteTableIT` again requires exactly 403. `MeController` checks the authentication type.
  - **Paging:** `PageQuery` rejects an overflowing offset and duplicate or excess sorts, and parses defaults strictly; `@Sortable` is validated at startup.
  - **Sample rules:** a NUL in `name`/`description` → 400; names are stripped. Restore and delete races answer 409/404 correctly; the dead re-check is removed; restore detaches only its entity and takes time and actor from the auditing beans.
  - **Contract:** `@NotBlank` → `minLength: 1`; response `required`/nullable; explicit operation IDs; `api.yaml` regenerated.
  - **Shared tests outside the sample:** `PageQueryBindingIT`, `EnvelopeDocumentationIT` and `OpenApiExportIT` assertions.
  - **More sample tests:** `SampleRecordApiIT` now has 46 tests.
  - **Migration:** a partial list index.
  - **ArchUnit:** the audit-field rule covers every `@RequestBody` type, honouring `@JsonProperty`.
  - **Other:** `ReadinessIT` keeps the main locations; README removal steps cover existing databases, the leftover mentions and the ownership note.
- Removability re-checked by the main agent on the final code. I applied README steps 1–3 to a scratch copy of `backend/` (deleted the main and test `feature/sample`, `db/migration/sample`, and the Flyway `sample` location, schema and comment), then ran `./gradlew updateOpenApi` and `./gradlew clean build`: both passed, and the regenerated `api.yaml` has only `getCurrentUser`. Remaining mentions of "sample" are Javadoc examples in `ErrorCode`, `ConstraintErrorMapping` and a fixture.
- Final verification (main agent): `./gradlew clean build` passes with 654 tests in 65 suites and 0 failures (50 s). snap-guard: 0 findings across 69 files. Doubt scan: clean. Context lake: none.

## Spec Change Log

## Review Triage Log

Review pass 1 (5 layers: blind, edge-case, verification-gap, slop, security; 61 raw findings). Duplicates across layers share one row.

| # | Layer | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|---|
| R1 | security + blind + verif-gap | Body binding and validation run before `@PreAuthorize`: a role-less caller gets `VALIDATION_FAILED` details (schema leak), and nothing proves 403 on POST/PUT (`RouteTableIT` weakened to 400-or-403) | high | MVC resolves and validates arguments before the method-security proxy runs; `RouteTableIT` sends `{}`. | patch (P1) |
| R2 | security + edge | `page` × `size` above `Integer.MAX_VALUE` → 500 | medium | Spring Data JPA 4.1.2 `PageableUtils` throws `InvalidDataAccessApiUsageException`; the handler maps it to 500 at ERROR. | patch (P2) |
| R3 | security + blind | Unbounded and duplicate `sort` parameters | medium | Every value becomes an `ORDER BY` term; duplicates are kept by design in a test. | patch (P2) |
| R4 | blind + edge + verif-gap | `@Sortable.defaultSort` not checked against `fields`; `parseOrder("")` accepts an empty field | medium | Fails at request time with a 500 on every list call. | patch (P2) |
| R5 | security | A NUL character in `name`/`description` likely gives a 500 | medium | PostgreSQL `text` rejects `\u0000` (SQLSTATE 22021) → unmapped `DataIntegrityViolationException`. Not run; the code path is clear. | patch (P3) |
| R6 | security + blind + edge | Names not normalised: `"Example"` and `"Example "` coexist | low | The raw name feeds the partial unique index; trimming is a direct correction (case stays significant, documented). | patch (P3) |
| R7 | edge | Concurrent restores: the loser gets 404 for an existing record; concurrent deletes: the loser gets 409 instead of 404 | medium | Check-then-act in `restore`; the optimistic lock on delete surfaces as `CONCURRENT_MODIFICATION`. | patch (P4) |
| R8 | blind | Dead version re-check in `JpaSampleRecordRepository.save`, whose message misreports a delete | low | The entity is already managed, so the filter never fires. | patch (P4) |
| R9 | edge | `restore()` clears the whole persistence context | low | Story 6's reference pattern; detaching only the restored row is direct. | patch (P4) |
| R10 | security + blind + edge | `MeController` casts the authentication unchecked (`ClassCastException` → 500) | low | Any non-JWT authentication; `@AuthenticationPrincipal Jwt` is direct. | patch (P5) |
| R11 | blind | README removal breaks an existing database (Flyway "applied migration not resolved locally") and leaves sample mentions in README/validation-rules | medium | `validate-on-migrate: true`; no ignore pattern. | patch (P6) |
| R12 | blind | Contract: `@NotBlank` names are `minLength: 0`; feature response schemas have no `required`/nullability; generic operation IDs (`list`, `get`, …) will collide | medium | springdoc does not map `@NotBlank`; IDs are method names. | patch (P7) |
| R13 | verif-gap | Shared paging binding and envelope documentation are tested only by sample tests that removal deletes | medium | Pre-verified. | patch (P8) |
| R14 | verif-gap | Envelope `required` lists and nullable `FieldError.field` are checked only by the byte snapshot | medium | Pre-verified. | patch (P8) |
| R15 | blind | Matrix and edge cases untested: WARN on 403, DELETE of an unknown or deleted id, non-UUID id, malformed JSON, double delete, delete after restore | medium | Rows without a covering test. | patch (P9) |
| R16 | blind | `docs/validation-rules.md` accept/reject samples not tested (whitespace name, 100/1000 boundaries, empty description) | low | The doc promises shared samples. | patch (P9) |
| R17 | blind | List index does not match the list query | low | The migration is unreleased; a partial `(created_at DESC, id) WHERE deleted_at IS NULL` index is direct. | patch (P10) |
| R18 | blind + edge | Audit-field ArchUnit rule only checks `*Request` record component names | low | Misses class DTOs, other names and `@JsonProperty` renames; checking every `@RequestBody` type is direct. | patch (P11) |
| R19 | verif-gap | `ReadinessIT` cases replace the Flyway locations, dropping `sample` | low | Passes only because the indicator ignores unresolved applied rows. | patch (P11) |
| R20 | slop | Correlation regex duplicated; `ERROR_STATUSES_TEXT` constant only for Javadoc; restore re-derives time and actor instead of using the auditing beans | low | Direct reuse and deletion. | patch (P11) |
| R21 | security | Any USER reads and edits every record (no ownership) | low | Shared demo dataset by design (security.md §3 sample rows); documenting where ownership checks go is direct. | patch (P6, docs) |
| R22 | security | `createdBy`/`updatedBy` reveal other users' subjects | low | Intended audit display for the demo (CAP-6). | rejected |
| R23 | security | Raw paging values echoed in `errors[]` | low | Existing `FieldError.rejectedValue` behaviour; JSON plus `nosniff`. | rejected |
| R24 | security | App role can hard-delete sample rows | false | Story 6 R11 decision (data-and-migrations.md §10). | rejected |
| R25 | security | Restore distinguishes active, deleted and missing | false | Intended responses (409/200/404), ADMIN-only. | rejected |
| R26 | slop | `PageQuery.offset()` unused | false | P2's overflow check uses it. | rejected |
| R27 | blind | Customizer decides "paged" from the parameter, not the return type | low | Every `PageQuery` handler returns a page by convention; a rule adds complexity. | rejected |
| R28 | edge | `created()` without `@ResponseStatus(CREATED)` is documented as 200 | low | The sample annotates it; a future mismatch is visible in `api.yaml` review. | rejected |
| R29 | edge | A bodiless 204 would be documented with an `ApiResponse` body | low | No 204 handler exists; the envelope rule forbids bodiless 2xx. | rejected |
| R30 | edge | `FieldError` schema without properties gives an NPE | low | swagger-core always emits properties for the record. | rejected |
| R31 | edge | `PageResult.map` with a null-returning mapper | low | Bare NPE; mappers are code, not input. | rejected |
| R32 | edge | `/me` with a token lacking `sub` | false | 7a rejects tokens without `sub`. | rejected |

## Design Notes

**Why the version check happens twice:** the service compares the request's `version` with the loaded aggregate, so a client holding a stale copy gets 409 even when no concurrent write is in flight. `@Version` still catches a race between the load and the commit.

**Why domain exceptions extend `SeedException`:** `common.error` is framework-free, so the domain can throw `SampleRecordNotFoundException extends ResourceNotFoundException` without importing Spring, and `GlobalExceptionHandler` already maps it.

## Verification

**Commands:**
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd backend && ./gradlew clean build` (Docker running) -- expected: BUILD SUCCESSFUL, all suites green, `checkOpenApi` green after `updateOpenApi`.

## Tech Spec Critic Findings

- [SOLVED] [high] check-1 | spec line 80 | Removability (CAP-20) is contradicted by the plan: the OpenAPI assertion in the shared `OpenApiExportIT` targets `POST /api/v1/sample-records` and the committed `api.yaml` includes sample endpoints, so deleting only the folder, migration folder and Flyway location fails `checkOpenApi`; neither the removability rule nor the README task says `api.yaml`, `docs/validation-rules.md` and the sample tests must also be removed or regenerated.
- [SOLVED] [medium] check-3 | spec line 102 | No row covers restoring a soft-deleted record whose name an active record now holds (409 `SAMPLE_RECORD_ALREADY_EXISTS` vs an unmapped 500).
- [SOLVED] [medium] check-1 | spec line 101 | Nothing states or tests that a soft-deleted record drops out of the paged list (including `totalElements`) and comes back after restore.
- [SOLVED] [medium] check-1 | spec line 98 | The concurrent-update `@Version` race (two updates with the same current version) has no row stating the loser sees 409 rather than 500.
- [SOLVED] [medium] check-5 | spec line 42 | PUT with `description` omitted or null: full replace (clears) or keep is unstated.
- [SOLVED] [medium] check-5 | spec line 57 | `sort=name` without a direction: accepted with a default or rejected as a bad direction is unstated.
- [SOLVED] [medium] check-5 | spec line 59 | "an out-of-range page" reads as `page < 0` only or also a page past the last one (200 empty vs 400); no row covers a page past the end.
- [SOLVED] [low] check-3 | spec line 96 | Updating a record's name to another active record's name has no row (409 on PUT).
- [SOLVED] [low] check-5 | spec line 59 | Non-numeric `page`/`size` could be 400 `VALIDATION_FAILED` or 400 `MALFORMED_REQUEST` (api-standards §2 type mismatch).
- [SOLVED] [low] check-3 | spec line 101 | "deleted_* cleared" cannot be observed (the response has no `deleted_*`), and the restore row omits `version`/`updatedAt`/`updatedBy`.
- [SOLVED] [low] check-2 | spec line 77 | `/me` returns an ADMIN only `["ADMIN"]` without telling the frontend that ADMIN implies USER; UI gating on `roles` could hide USER features from admins.
- [SOLVED] [low] check-2 | spec line 38 | The envelope `message` for sample endpoints and `/me` is unspecified.
- [SOLVED] [low] check-5 | spec line 107 | Removability verification is left open between a test task and a documented manual check.
- [SOLVED] [low] check-6 | spec line 124 | PostgreSQL features, Spring MVC argument resolution (the `PageQuery` binding) and Spring Security method security are relied on but not listed.
