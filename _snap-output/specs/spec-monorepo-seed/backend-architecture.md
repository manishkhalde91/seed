# Backend architecture, monorepo layout and module design

The backend is one Spring Boot application with the seed layout:
- **Top level:** `config/`, `common/` and `security/`.
- **Features:** each feature module sits under `feature/` and is split into `api/`, `application/`, `domain/` and `infrastructure/`.

Dependencies point inward:
- `api/` and `infrastructure/` depend on `application/` and `domain/`.
- `application/` and `domain/` depend on nothing web-, persistence- or vendor-specific.

Data access follows the Repository Pattern (§5). The diagram is in [architecture-diagrams.md](architecture-diagrams.md) §2.

## 1. Monorepo directory structure

```
seed-monorepo/
├─ backend/
│  ├─ gradlew, gradlew.bat, gradle/wrapper/  Gradle wrapper, committed, with distributionSha256Sum
│  ├─ gradle/libs.versions.toml            version catalog: every pinned version
│  ├─ settings.gradle.kts                  rootProject.name = "seed"; single project
│  ├─ build.gradle.kts                     Boot plugin 4.1.x; Boot and Modulith BOMs via platform()
│  ├─ Dockerfile                           multi-stage, layered jar, non-root
│  ├─ openapi/api.yaml                     generated, committed (CAP-15)
│  └─ src/
│     ├─ main/java/com/company/seed/
│     │  ├─ SeedApplication.java           entry point, @Modulithic
│     │  ├─ config/                        framework configuration (§4.2)
│     │  ├─ common/                        cross-cutting kernel, OPEN module (§4.3)
│     │  │  ├─ api/                        response and pagination contracts
│     │  │  ├─ error/                      exceptions, error codes, global exception handler
│     │  │  ├─ logging/                    correlation ID, MDC, masking
│     │  │  ├─ auditing/                   AuditorAware, auditing config, base entities
│     │  │  └─ actor/ validation/ ids/ idempotency/ jobs/ operations/ storage/
│     │  ├─ security/                      security filter chain, JWT validation, role mapping (§4.4)
│     │  └─ feature/                       feature modules; not itself a module
│     │     └─ sample/                     DEMO ONLY, removable (§4.5)
│     │        ├─ api/
│     │        ├─ application/
│     │        ├─ domain/
│     │        └─ infrastructure/
│     ├─ main/resources/
│     │  ├─ application.yml                shared defaults; values from environment variables
│     │  ├─ application-local.yml          compose endpoints, mock OIDC, Swagger UI on, Flyway on startup
│     │  ├─ application-dev.yml, -qa.yml, -prod.yml   non-secret differences only
│     │  ├─ application-migrate.yml        Flyway on, web off, exits after migration
│     │  └─ db/migration/<module>/         Flyway scripts, one folder per module (+ common)
│     └─ test/
│        ├─ java/com/company/seed/
│        │  ├─ architecture/               ModularityTest, ArchUnit rules
│        │  ├─ common/ …, security/ …, feature/sample/ …   mirror main
│        │  └─ support/                    Testcontainers config, TestJwt, contract test bases
│        └─ resources/application-test.yml
├─ frontend/                               Angular 21 workspace (frontend-architecture.md)
│  ├─ package.json, angular.json, Dockerfile (optional)
│  └─ src/app/{core,shared,layout,features}/
├─ infrastructure/
│  ├─ compose.yaml                         postgres, minio, mock-oauth2 (+ sonarqube, app containers via profiles)
│  ├─ postgres/                            init script: database, seed_owner and seed_app roles
│  └─ sonarqube/                           optional local SonarQube settings
├─ docs/
│  ├─ adr/                                 ADRs from decisions.md, one file each
│  ├─ adding-a-feature.md                  §8 as a step-by-step guide
│  └─ validation-rules.md                  backend ↔ frontend rule parity
├─ .github/workflows/ci.yml                path-filtered backend and frontend jobs + ci-gate
├─ .github/CODEOWNERS, PULL_REQUEST_TEMPLATE.md, dependabot.yml
├─ .editorconfig, .gitattributes, .gitignore, .nvmrc, .sdkmanrc
├─ .env.example                            variable names with placeholders; real .env untracked
├─ sonar-project.properties                frontend analysis; backend scans via the `org.sonarqube` Gradle plugin
└─ README.md                               prerequisites, one local setup path, commands, links
```

| Directory | Purpose |
|---|---|
| `backend/` | The Spring Boot application, its tests and its image. Builds alone with `./gradlew clean build`. |
| `frontend/` | The Angular application. Builds alone with `npm ci && npm run build`. |
| `infrastructure/` | Local developer infrastructure only. Not a deployment definition. |
| `docs/` | ADRs and how-to guides. Documentation distinguishes what is implemented from what is planned. |
| `.github/workflows/` | CI. Path filters keep backend and frontend jobs independent. |

**Repository rules (seed §5.1)**
- Backend and frontend have independent build and test commands. There is no root build orchestrator.
- The root README gives one local setup path and links to detailed guides.
- Secrets stay out of git. `.env.example` holds placeholders only.
- Formatting comes from `.editorconfig` plus Spotless and Prettier.

**Gradle strategy.** `backend/` is a single Gradle project, because Spring Modulith verifies modules as packages under one root. Add subprojects only when a module is carved out into its own service. No `buildSrc` or convention plugins until a second project exists.

## 2. Profiles

| Profile | Purpose | Expectations |
|---|---|---|
| `local` | Developer workstation | Compose endpoints, verbose diagnostics, Swagger UI on, Flyway on startup |
| `test` | Automated tests | Testcontainers, deterministic configuration, Flyway on startup |
| `dev` | Shared development | Externalised secrets, controlled log level, Swagger UI behind authentication |
| `qa` | Quality assurance | Externalised secrets; migration validation |
| `prod` | Production | HTTPS, secrets from the platform, minimal logs, docs off |
| `migrate` | One-off run before each deploy | Flyway as `seed_owner`, no web server, exits 0 or 1 |

Profile names are conventions. Values come from environment variables or a secret store, never from committed YAML.

## 3. Spring Modulith module design

| Module | Package | Type | Responsibility |
|---|---|---|---|
| `config` | `com.company.seed.config` | closed; nothing depends on it | Framework configuration |
| `common` | `com.company.seed.common` | `@ApplicationModule(type = OPEN)`, shared | Cross-cutting kernel (§4.3) |
| `security` | `com.company.seed.security` | closed, shared | Authentication, authorization, actor resolution |
| `sample` | `com.company.seed.feature.sample` | closed | Demonstration feature (§4.5); delete in real projects |

**Module detection.**
- `SeedApplication` carries `@Modulithic(additionalPackages = "com.company.seed.feature", sharedModules = {"common", "security"})`.
- `spring.modulith.detection-strategy=explicitly-annotated`.
- Every module root has a `package-info.java` with `@ApplicationModule`. `feature` has none.
- `ModularityTest` asserts the detected set is exactly `config`, `common`, `security` plus the feature modules.
- This setup needs confirming on Modulith 2.1 in roadmap phase 1.

**Rules**
1. A module's **root package is its public API**: facade, DTO records and event records. Every sub-package is internal, so repositories, services and adapters are never reachable from another module.
2. Cross-module calls go through the other module's facade (query) or a published event (state change). A module never uses another module's repositories.
3. Events are immutable `record`s in the publisher's root package, named in the past tense (`SampleExportRequested`).
4. Listeners use `@ApplicationModuleListener`: after commit, in a new transaction, async, recorded in the event publication registry and resubmitted after a restart.
5. No cycles. `security` depends on `common`; `common` never depends on `security` or on a feature module.
6. Each feature module has `<Module>ModuleTests` using `@ApplicationModuleTest`.
7. `common` holds only what is genuinely cross-cutting. It never holds feature DTOs or feature logic.

## 4. Package structure

### 4.1 Feature module shape

```
com.company.seed.feature.<module>/
├─ package-info.java          @ApplicationModule(displayName = "...")
├─ <Module>Facade, events, DTO records          PUBLIC API (root package)
├─ api/                       REST controllers, request/response records, web mappers
├─ application/               use-case services (@Transactional), event listeners
│  └─ port/                   outbound interfaces to non-database systems, only when needed
├─ domain/                    plain Java: aggregates, value objects, status enums,
│                             domain exceptions, <Aggregate>Repository interfaces (§5)
├─ infrastructure/
│  ├─ persistence/            Jpa<Aggregate>Repository + JPA entity, Spring Data interface, mapper (§5)
│  └─ <tech>/                 other adapters (scheduling, external clients) only when needed
└─ config/                    module @Configuration, @ConfigurationProperties records
```

A module creates only the sub-packages it needs.

### 4.2 `config`

```
com.company.seed.config/
├─ package-info.java          @ApplicationModule
├─ JacksonConfig.java         Jackson 3 defaults (UTC, ISO-8601)
├─ ClockConfig.java           UTC Clock bean
├─ OpenApiConfig.java         springdoc; Swagger UI only where docs are enabled
├─ AsyncConfig.java           bounded executor for event listeners; copies MDC and security context
└─ SchedulingConfig.java      scheduler pool
```

### 4.3 `common`

```
com.company.seed.common/
├─ package-info.java          @ApplicationModule(type = OPEN)
├─ api/                       ApiResponse<T>, ApiErrorResponse, ApiResponses factory, PageQuery, PageResult<T>
├─ error/                     SeedException hierarchy, ErrorCode (no Spring imports)
│  └─ web/                    GlobalExceptionHandler (@RestControllerAdvice), ApiErrorResponseWriter
├─ logging/                   CorrelationIdFilter, CorrelationContext (MDC), LogSafe, masking customizer
├─ auditing/                  AuditableEntity, SoftDeletableEntity, JpaAuditingConfig, AuditorAware bean
├─ actor/                     CurrentActor port; SystemActor scope (system:<name>)
├─ validation/                OnCreate / OnUpdate groups, shared constraint annotations
├─ ids/                       IdGenerator (UUIDv7)
├─ idempotency/               Idempotency-Key filter; IdempotencyRecordRepository + JPA implementation; cleanup
├─ jobs/                      ScheduledJob: @Scheduled wrapper with PostgreSQL advisory lock, MDC, metrics
├─ operations/                generic long-running operation status (realtime-and-storage.md §1)
│  ├─ api/                    GET /api/v1/operations/{id}, STOMP snapshot @SubscribeMapping
│  ├─ application/            OperationService: start, markRunning, complete, fail (version + pg_notify)
│  ├─ domain/                 Operation, OperationStatus, OperationRepository
│  └─ infrastructure/         persistence/JpaOperationRepository, notify/PgStatusListener, ws/StompConfig
└─ storage/                   ArtifactService facade, ArtifactRef (realtime-and-storage.md §2)
   ├─ api/                    ArtifactController (download URL)
   ├─ application/port/       ArtifactStorage (object storage, not a repository)
   ├─ domain/                 Artifact, ArtifactRepository (metadata rows)
   └─ infrastructure/         persistence/JpaArtifactRepository, s3/S3ArtifactStorage,
                              fake/InMemoryArtifactStorage, scheduling/ArtifactOrphanCleanupJob
```

`common` owns the `common` schema (`operation`, `artifact`, `idempotency_record`, Modulith `event_publication`) and `db/migration/common`.

### 4.4 `security`

```
com.company.seed.security/
├─ package-info.java          @ApplicationModule
├─ SecurityConfig.java        filter chain, deny by default, resource server, headers, CORS
├─ JwtRoleConverter.java      configurable claim → ROLE_*; ADMIN implies USER
├─ AudienceValidator.java     configurable audience
├─ web/                       JsonAuthenticationEntryPoint (401), JsonAccessDeniedHandler (403) → envelope
├─ ws/                        StompAuthChannelInterceptor: JWT on STOMP CONNECT
└─ infrastructure/            SecurityContextActor implements common.actor.CurrentActor
```

### 4.5 `sample` (demonstration only)

```
com.company.seed.feature.sample/
├─ package-info.java          "DEMO — delete in real projects"
├─ SampleExportRequested.java event
├─ api/SampleRecordController.java   CRUD, restore, paging, POST /export (202)
├─ api/CreateSampleRecordRequest, UpdateSampleRecordRequest, SampleRecordResponse
├─ application/SampleRecordService.java   @Transactional use cases
├─ application/SampleExportListener.java  @ApplicationModuleListener: builds a CSV, stores it, completes the operation
├─ domain/SampleRecord.java, SampleRecordRepository.java
├─ infrastructure/persistence/JpaSampleRecordRepository.java
│                             + SampleRecordJpaEntity, SampleRecordSpringDataRepository, SampleRecordPersistenceMapper
└─ config/SampleProperties.java
```

## 5. Repository Pattern

A repository makes stored aggregates look like an in-memory collection. Application services ask it for domain objects and hand domain objects back. They never see SQL, JPA entities or Spring Data.

| Piece | Where | Visibility | Responsibility |
|---|---|---|---|
| `<Aggregate>Repository` (interface) | `feature/<module>/domain/` | module-internal | Contract in domain language; plain Java; domain types, `Optional`, `List`, `common.api.PageResult` only |
| `Jpa<Aggregate>Repository` (implementation) | `feature/<module>/infrastructure/persistence/` | module-internal package | Implements the interface; maps domain ↔ entity; runs queries through Spring Data or `JdbcClient` |
| `<Aggregate>JpaEntity` | same package | package-private | Table mapping; extends `AuditableEntity` / `SoftDeletableEntity` |
| `<Aggregate>SpringDataRepository` | same package | package-private | Extends Spring Data `Repository<E, ID>` (not `JpaRepository`); declares only the methods needed |
| `<Aggregate>PersistenceMapper` | same package | package-private | Hand-written domain ↔ entity mapping |

**Rules**
1. One repository per aggregate root, named after it. Child tables are reached through their aggregate.
2. Methods state what the caller needs (`save`, `findById`, `findPage`, `restore`). No generic query builders.
3. Data access only: no business rules, no events, no calls to other repositories or external systems.
4. Transactions belong to the application service; repositories join them.
5. Only `application/` calls repositories. `api/` never does, and no other module does.
6. JPA entities and Spring Data types never cross the interface. Every aggregate is mapped, including simple ones.
7. No generic base repository. Soft-deletable aggregates expose no `delete` because the Spring Data interface declares none.
8. Each implementation has a `*PersistenceIT` against PostgreSQL. Services are unit-tested with a mocked repository.

ArchUnit enforces:
- `..domain..` imports no `jakarta.persistence`, `org.springframework` or `..infrastructure..`.
- Spring Data interfaces exist only in `..infrastructure.persistence..`.
- `..api..` depends on no `*Repository`.
- `..application..` imports no `..infrastructure..`.

## 6. Layer responsibilities

| Layer | Packages | Owns | Must not |
|---|---|---|---|
| Config | `config` | Framework and infrastructure configuration | contain feature-specific rules |
| Common | `common/*` | Envelope, errors, logging, auditing and the other cross-cutting pieces in §4.3 | hold feature DTOs; expose stack traces or SQL; log credentials or full payloads; assume every record is soft-deleted |
| Security | `security` | Filter chain, JWT validation, role mapping, actor resolution | rely on frontend guards |
| Public API | feature module root | Facade, DTO and event records | contain logic beyond delegating to `application/` |
| API | `api/` | Request binding, `@Valid`, `@PreAuthorize`, DTO ↔ command mapping, envelope building | contain business rules, open transactions, call repositories or import `infrastructure/` |
| Application | `application/` | Use cases, transaction boundaries, domain calls, repositories and ports, events | depend on HTTP request objects; import JPA, web or vendor types |
| Domain | `domain/` | Invariants, state transitions, value objects, domain exceptions, repository interfaces | import Spring, Spring MVC, JPA, Jackson or vendor SDKs |
| Infrastructure | `infrastructure/<tech>/` | Repository implementations, port implementations, scheduled triggers | leak JPA entities or vendor types through interfaces or into API responses; import another `infrastructure/<tech>` |

**Dependency injection and transactions (seed §6.2).** Constructor injection only. `@Transactional` on application services; `readOnly = true` for queries; short transactions with no network calls inside; explicit fetch strategies to avoid N+1; no generic base services or repositories.

## 7. One request, step by step (sample create)

| # | Class | Layer | What happens |
|---|---|---|---|
| 1 | `CorrelationIdFilter` | common | reads or creates the correlation ID, puts it in MDC |
| 2 | `SecurityConfig` filter chain | security | validates the JWT; 401 / 403 in the error envelope |
| 3 | `SampleRecordController.create()` | api | binds and validates `CreateSampleRecordRequest`, checks `@PreAuthorize`, calls the service |
| 4 | `SampleRecordService.create()` | application | `@Transactional`: builds `SampleRecord`, calls `SampleRecordRepository.save` |
| 5 | `JpaSampleRecordRepository.save()` | infrastructure | maps to `SampleRecordJpaEntity`; JPA auditing fills audit columns |
| 6 | PostgreSQL | external | row inserted in `sample.sample_record` |
| 7 | `ApiResponses.created(...)` | common | 201 envelope with `Location`; any exception goes through `GlobalExceptionHandler` instead |

## 8. Adding a feature module

1. Create `com.company.seed.feature.<x>` with `package-info.java` and only the sub-packages it needs (§4.1).
2. Add `db/migration/<x>/` and schema `<x>`; write the migration first.
3. Model the aggregate in `domain/` with its repository interface; implement it in `infrastructure/persistence/`.
4. Implement the use case in `application/`; add request/response records with validation and a thin controller in `api/`.
5. Add `@PreAuthorize` rules and tests for allowed and denied access; add `<X>ModuleTests` and a `*PersistenceIT`.
6. Regenerate `openapi/api.yaml`; add the frontend feature with its typed service and tests.
7. Update docs; run all checks; get a code review.
