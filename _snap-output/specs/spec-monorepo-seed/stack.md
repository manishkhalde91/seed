# Stack and version compatibility

These versions were checked on 10 Nov 2026. At scaffold time, pin the **latest patch** within each line and record it in `backend/gradle/libs.versions.toml` / `package-lock.json`. Never use floating versions. Rows marked *verify* are not covered by an official compatibility matrix; confirm them when scaffolding.

## Backend

| Component | Line to pin | Managed by | Compatibility notes |
|---|---|---|---|
| JDK | Temurin 21 LTS | — | The Boot 4.x baseline is Java 17, and 21 is supported. |
| Build | Gradle via the committed wrapper (`gradlew`, `gradle/wrapper/`), Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts`), version catalog `gradle/libs.versions.toml` | — | No global Gradle install. Wrapper properties carry `distributionSha256Sum`. Pin the newest Gradle release the Boot 4.1 plugin supports (*verify*). |
| Spring Boot | **4.1.x** (OSS support to 31 Jul 2027) | Boot Gradle plugin + `platform()` BOM | 4.0.x OSS support ends around Dec 2026. |
| Spring Framework | 7.0.x | Boot | Jakarta EE 11: Servlet 6.1, Validation 3.1, Persistence 3.2. |
| Spring Modulith | **2.1.x** via `spring-modulith-bom` | own BOM | 2.1 pairs with Boot 4.1. Artifacts: `spring-modulith-starter-core`, `spring-modulith-starter-jpa` (event publication registry), `spring-modulith-starter-test`. |
| Spring Security | 7.0.x | Boot | `spring-boot-starter-oauth2-resource-server`; lambda DSL only. |
| Hibernate ORM | 7.x | Boot | `@Where` is removed; use `@SQLRestriction`. `@SoftDelete` cannot store `deleted_by`, so it is not used. |
| Hibernate Validator | 9.x | Boot | `spring-boot-starter-validation`. |
| Jackson | **3.x** (`tools.jackson`) | Boot | Annotations stay in `com.fasterxml.jackson.annotation`. Do not create a Jackson 2 `ObjectMapper`. |
| Flyway | 11.x via `spring-boot-starter-flyway` + `flyway-database-postgresql` | Boot | Boot 4 auto-configuration is modular, so the Flyway starter is required (*verify* the artifact name). |
| PostgreSQL | 16, with JDBC driver 42.7.x | Boot | — |
| Web | `spring-boot-starter-webmvc` | Boot | Boot 4 renames `spring-boot-starter-web`. |
| WebSocket | `spring-boot-starter-websocket` | Boot | STOMP over the simple in-memory broker. |
| OpenAPI | springdoc-openapi **3.x** `springdoc-openapi-starter-webmvc-ui` | explicit | 2.x supports Boot 3 only. Pin a 3.x patch that declares Boot 4.1 (*verify*). |
| Observability | Actuator, Micrometer, Prometheus registry, optional OTLP tracing, built-in structured logging (SLF4J + Logback) | Boot | `logging.structured.format.console=ecs`. |
| IDs | `com.github.f4b6a3:uuid-creator` 6.x | explicit | UUIDv7. |
| Object storage | AWS SDK for Java v2 via `software.amazon.awssdk:bom` 2.x: `s3` (+ `S3Presigner`) | own BOM | Used only in `common.storage.infrastructure.s3`. Path-style access switchable for MinIO and other S3-compatible stores. |
| Tests | JUnit Jupiter **6.x** (the JUnit 5 API style is unchanged), Mockito 5, AssertJ, Testcontainers **2.x** (`testcontainers-postgresql`, `testcontainers-minio`), ArchUnit 1.4.x, Spring Modulith test, Awaitility | Boot / explicit | Testcontainers 1.x imports do not compile against 2.x. |
| Quality | `jacoco` plugin with tool ≥ 0.8.13, `org.sonarqube` Gradle plugin (*verify* latest major), Spotless Gradle plugin `com.diffplug.spotless` (google-java-format pinned), Java toolchain 21, `failOnVersionConflict()` | explicit | JaCoCo below 0.8.11 cannot read Java 21 bytecode. |

Not used by default: Lombok, MapStruct, ShedLock, Kafka. [decisions.md](decisions.md) gives the reasons.

## Frontend

| Component | Line to pin | Compatibility notes |
|---|---|---|
| Angular (core, router, forms, common, animations) | **21.x** | LTS only (security fixes) until about May–Jun 2027. Standalone and zoneless are the defaults. |
| Angular CLI / `@angular/build` | 21.x | The default unit-test builder is `@angular/build:unit-test` (Vitest). |
| Angular Material + CDK | 21.x | M3 theming via `mat.theme()` driven by the SCSS tokens. |
| TypeScript | **5.9.x** | Angular 21 supports `>=5.9 <6.0`. |
| Node | 22.12+ or 24.x LTS | Pinned in `.nvmrc` and CI. |
| RxJS | 7.8.x | — |
| `@ngrx/signals` | 21.x | Its majors follow Angular. |
| `angular-auth-oidc-client` | 21.x (*verify* the peer range) | Authorization code + PKCE. |
| `ng-openapi-gen` | 1.x (*verify* the Angular 21 peer) | Generates `src/app/api/`. |
| `@stomp/stompjs` | 7.x | STOMP client. |
| `zod` | 4.x | Validates `config.json`. |
| Lint / format | angular-eslint 21, ESLint 9 flat config, stylelint 16 + `stylelint-config-standard-scss`, Prettier 3 | — |
| Tests | Vitest + `@vitest/coverage-v8` (lcov), TestBed, CDK/Material harnesses, Playwright 1.x (optional smoke) | — |

## Local infrastructure and tooling

| Concern | Choice |
|---|---|
| Local services | `infrastructure/compose.yaml`: `postgres:16`, `minio/minio`, `ghcr.io/navikt/mock-oauth2-server`, optional `sonarqube` (LTA) under a compose profile. All images pinned by tag and digest |
| Backend image | Eclipse Temurin 21 JRE base, multi-stage, non-root |
| Frontend image | optional: `nginxinc/nginx-unprivileged` serving `dist/` |
| CI | GitHub Actions (another platform runs the same stages) |
| Static analysis | SonarQube Server LTA or SonarQube Cloud; Dependabot or Renovate; Gitleaks; Trivy image scan; CycloneDX SBOM |

## Known compatibility risks

1. **Jackson 3.** Modulith event serialisation, the WebSocket converter and springdoc must all use the Boot-managed `JsonMapper`. An ArchUnit rule bans `com.fasterxml.jackson.databind` in application code.
2. **Boot 4 modular starters.** Each technology (webmvc, Flyway, WebSocket, test slices) needs its own starter. If one is missing, the feature silently has no auto-configuration. A context test asserts that the key beans exist.
3. **springdoc lag.** springdoc ships after each Boot minor release. If they conflict, hold the Boot patch rather than springdoc.
4. **Gradle dependency convergence.** `failOnVersionConflict()` may flag conflicts the Boot platform already resolves. If it does, narrow it to the runtime and test classpaths or replace it with an equivalent check, and record a README *verify* item.
5. **Angular 21 LTS window.** Third-party peer ranges may drop 21 before mid-2027. Pin exact versions and plan the upgrade (open question).
