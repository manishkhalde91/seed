# Testing, code quality, build, CI and local environment

## 1. Backend testing

| Level | Covers | Tooling | Naming |
|---|---|---|---|
| Domain unit | Invariants, value objects, state transitions | JUnit Jupiter, AssertJ, no Spring | `*Test` |
| Application service | Orchestration, transactions, events published | JUnit + Mockito (mock repository interfaces and ports only) | `*ServiceTest` |
| Web slice | Binding, validation → envelope, `@PreAuthorize`, status codes | `@WebMvcTest` + MockMvc + `TestJwt` | `*ControllerTest` |
| Repository | Mappings, queries, soft-delete restriction, partial indexes, restore | `@DataJpaTest` + PostgreSQL Testcontainer + Flyway | `*PersistenceIT` |
| Module | One module through its public API, events in and out | `@ApplicationModuleTest` + `Scenario` | `*ModuleTests` |
| API integration | Full stack: envelope contract over the route table, deny-by-default matrix, idempotency key, auditing | `@SpringBootTest(RANDOM_PORT)` + `RestClient` | `*ApiIT` |
| Realtime | CONNECT auth, subscribe rules, push across two app contexts, resync | `WebSocketStompClient` + Awaitility | `*StompIT` |
| Storage | Write sequence, presigned GET, orphan cleanup | MinIO Testcontainer | `*StorageIT` |
| Architecture | Modulith `verify()`; ArchUnit: layer rules, Repository Pattern rules (backend-architecture.md §5), `@PreAuthorize` on controllers, envelope return types, no field injection, no Jackson 2, no `@Transactional` + external port | JUnit | `architecture/*Test` |
| Contract | OpenAPI regenerated and compared with the committed file | Gradle | build step |
| Migrations | Fresh database migrate; `flyway validate` | Flyway + Testcontainers | `MigrationIT` |

- `ContainersConfig` uses `@ServiceConnection`; containers are reused within a JVM.
- The `test` task runs `*Test`; an `integrationTest` task runs `*IT` from the same source set; `check` depends on both, so both run in `./gradlew build`.
- Time comes from a `MutableClock`; tests never sleep (Awaitility).
- Tests never depend on a developer's database or the internet.
- Names: `should<Outcome>_when<Condition>`; Given/When/Then comments.

## 2. Frontend testing

| Level | Tooling |
|---|---|
| Component | Vitest + TestBed, Material/CDK harnesses |
| Service / store | `HttpTestingController`; stores via their methods and computed signals |
| Forms | Every validator; one test per form builder (initial state, shared valid/invalid samples, server-error mapping) |
| Interceptors / guards | `TestBed.runInInjectionContext`; `RouterTestingHarness` |
| Realtime | `StompClientService` with a fake client; version ordering and resync |
| End-to-end | Optional Playwright smoke in `frontend/e2e/` against the local stack; not mandatory |

## 3. Coverage

| Scope | Line coverage |
|---|---|
| New code (Sonar gate) | ≥ 80% |
| Backend `domain` + `application` | ≥ 85% (proposed) |
| Frontend `core`, `shared/forms`, stores | ≥ 85% (proposed) |

The non-gate thresholds are proposals to confirm after a baseline measurement (open question); they must not produce meaningless tests. Generated code, configuration classes and `SeedApplication` are excluded.

## 4. Static analysis and SonarQube

- **Backend:** `org.sonarqube` Gradle plugin (`./gradlew sonar`) after `build`; JaCoCo XML merging `test` and `integrationTest` via `sonar.coverage.jacoco.xmlReportPaths`; `sonar.java.source=21`.
- **Frontend:** SonarScanner with `sonar-project.properties`; `sonar.javascript.lcov.reportPaths=frontend/coverage/lcov.info`; `frontend/src/app/api/**` excluded.
- Two projects, `seed-backend` and `seed-frontend`, each analysed by its own CI job.
- **Quality gate on new code** (configured on the Sonar server by the organisation):

| Metric | Threshold |
|---|---|
| Coverage | ≥ 80% |
| Duplicated lines | ≤ 3% |
| New bugs / vulnerabilities | 0 (ratings A) |
| Security hotspots reviewed | 100% |
| Maintainability | rating A (debt ratio ≤ 5%) |

- Without `SONAR_TOKEN` (forks, fresh copies), the Sonar step is skipped and the job summary says so explicitly.
- Findings are suppressed only with a documented reason and narrow scope.
- Also: Spotless (google-java-format), Prettier, ESLint, stylelint, CodeQL (optional), Gitleaks, Trivy, `npm audit`, Dependabot/Renovate.
- `infrastructure/compose.yaml` offers an optional local SonarQube under the `sonar` profile.

## 5. Build

- **Backend:** `./gradlew clean build` (compile, unit and integration tests, ArchUnit, Modulith checks, `spotlessCheck`, JaCoCo, OpenAPI). `bootJar` builds a layered jar. The Java toolchain requires JDK 21 with auto-provisioning off; `failOnVersionConflict()` enforces dependency convergence.
- **Frontend:** `npm ci && npm run lint && npm run test:ci && npm run build`. Budgets: initial bundle ≤ 500 kB warning, ≤ 1 MB error; component styles ≤ 6 kB.

## 6. Container images

| Image | Base | Notes |
|---|---|---|
| `seed-backend` | build: `eclipse-temurin:21-jdk`; runtime: `eclipse-temurin:21-jre`, pinned by digest | Multi-stage, layered jar, non-root UID 10001, `-XX:MaxRAMPercentage=75 -Duser.timezone=UTC`; profile from `SPRING_PROFILES_ACTIVE` |
| `seed-frontend` (optional) | build: `node:22`; runtime: `nginxinc/nginx-unprivileged`, pinned by digest | Serves `dist/`, `config.json` mounted per environment, CSP and security headers, SPA fallback |

No `latest` tags. Each image is tagged with the commit SHA and comes with an SBOM.

## 7. Local environment (`infrastructure/compose.yaml`)

| Service | Image | Port | Notes |
|---|---|---|---|
| `postgres` | `postgres:16` | 5432 (localhost only) | `infrastructure/postgres/` init creates `seed_db`, `seed_owner`, `seed_app`; named volume; `pg_isready` healthcheck |
| `minio` | `minio/minio` (+ `minio/mc` init creating the bucket) | 9000 / 9001 | Static local credentials only |
| `oidc` | `mock-oauth2-server` | 8090 | Issuer `http://localhost:8090/seed`; demo users `demo-user` (USER), `demo-admin` (ADMIN) |
| `sonarqube` | `sonarqube` LTA | 9002 | profile `sonar`; optional |
| `backend`, `frontend` | the seed images | 8080 / 4200 | profile `apps`; optional, developers can run both from the IDE instead |

Commands (README, Appendix B of the seed document):

```
docker compose -f infrastructure/compose.yaml up -d            # start infrastructure
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
cd frontend && npm ci && npm start
docker compose -f infrastructure/compose.yaml down              # stop
docker compose -f infrastructure/compose.yaml down -v           # stop and DELETE local data volumes
```

## 8. Environment configuration

- Non-secret differences live in `application-<profile>.yml` (log levels, pool sizes, flags). Endpoints and secrets come from environment variables.
- `@ConfigurationProperties` records with `@Validated` fail startup on a missing or invalid value.
- `.env.example` lists every variable (security.md §5).

## 9. CI pipeline (GitHub Actions)

`.github/workflows/ci.yml`, on pull requests and pushes to `main`:

| Job | Runs when | Steps |
|---|---|---|
| `changes` | always | `dorny/paths-filter`: `backend/**`, `frontend/**`, `backend/openapi/api.yaml`, shared files |
| `backend` | backend or shared changed | set up Java 21; `gradle/actions/setup-gradle` (wrapper validation, cache); `./gradlew build`; OpenAPI drift; Sonar `seed-backend` (or "skipped"); image build + Trivy |
| `frontend` | frontend, contract or shared changed | set up Node from `.nvmrc`; `npm ci`; lint; stylelint; `test:ci`; `api:check`; production build; Sonar `seed-frontend` (or "skipped") |
| `ci-gate` | always (`if: always()`) | fails only if a job that ran failed or was cancelled; the single required check |

- Test and coverage reports are published as artifacts.
- Secrets are never printed; `SONAR_TOKEN` is optional.
- Actions are pinned by commit SHA.
- Branch protection on `main`: PR required, one code-owner review, `ci-gate` green, no direct pushes.
- Deployment workflows are out of scope; a project adds its own.

## 10. Health, readiness and metrics

| Item | Value |
|---|---|
| Liveness | `/actuator/health/liveness` |
| Readiness | db, schema version, PG LISTEN connection |
| Metrics | `/actuator/prometheus` on the management port 8081 |
| Details | `show-details=when-authorized` (ADMIN) |

Key metrics: `http_server_requests_*`, `hikaricp_*`, JVM, `seed_job_duration_seconds`, `seed_ws_sessions_active`, `seed_operation_total{kind,status}`, `seed.storage.*`.

## 11. Graceful shutdown

- `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`.
- Readiness goes DOWN at the start of shutdown; STOMP sessions close with code 1012; the scheduler waits for running jobs; unfinished event publications are resubmitted on the next start.

## 12. Definition of done (seed §19.1)

- All capabilities' success checks pass from a clean checkout; evidence attached to the PR.
- No unexplained critical or high security issue; no committed credentials, tokens or keys.
- Migrations and fixtures reproducible; README commands tested from a clean checkout.
- Open decisions recorded with an owner and due date.
- Generated code reviewed by a human technical owner.
