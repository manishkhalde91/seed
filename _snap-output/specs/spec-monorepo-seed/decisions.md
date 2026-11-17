# Architectural decisions (ADRs)

Each ADR becomes `docs/adr/NNNN-<title>.md`. Format: Proposal · Why · Alternatives · Pros / Cons · Scalability.

## ADR-01 Java 21, Spring Boot 4.1.x, Spring Modulith 2.1.x

- **Proposal:** pin Java 21 LTS, Boot 4.1.x and Modulith 2.1.x at their latest patch.
- **Why:** current support window (Boot 4.1 OSS to Jul 2027); Java 21 language features; Modulith 2.1 pairs with Boot 4.1. This supersedes the seed document's Java 17 / Boot 3.x baseline by explicit decision.
- **Alternatives:** Java 17 + Boot 3.5 (the seed document's default): older line, a costlier upgrade later. Boot 4.0: support ends around Dec 2026.
- **Pros:** Jackson 3, Jakarta EE 11, modular starters.
- **Cons:** springdoc 3.x trails Boot minors; Jackson 3 migration for any reused code.
- **Scalability:** upgrade minors about every 6 months with Dependabot.

## ADR-02 Angular 21, standalone, zoneless, signal stores

- **Proposal:** Angular 21, Material 21, `@ngrx/signals` 21, TypeScript 5.9.
- **Why:** standalone and zoneless defaults; signal stores fit feature-scoped state.
- **Alternatives:** Angular 22 (active line).
- **Pros:** stable third-party peer ranges.
- **Cons:** LTS only, ending about mid-2027.
- **Scalability:** schedule the upgrade before EOL (open question).

## ADR-03 One application, one image, no worker or broker

- **Proposal:** a single Spring Boot application; environment profiles; a one-off `migrate` profile; async work in-process.
- **Why:** the seed's scope is infrastructure only; a broker and a worker role would be unused complexity for most projects.
- **Alternatives:** api + worker roles with Kafka (the SCO approach); separate services.
- **Pros:** simple to run and understand.
- **Cons:** heavy background workloads compete with request threads.
- **Scalability:** a project that needs it adds a worker profile and a broker; module boundaries and events already prepare the seam.

## ADR-04 Seed backend layout with the Repository Pattern

- **Proposal:**
  - `config/`, `common/`, `security/`, `feature/<module>/{api,application,domain,infrastructure}`.
  - Repository interfaces in `domain/`; `Jpa<Aggregate>Repository` implementations in `infrastructure/persistence/`; domain always separate from JPA entities.
  - Non-database outbound interfaces in `application/port/` only when needed.
- **Why:** the seed document's structure; one consistent data-access shape; services testable without a database.
- **Alternatives:** services calling Spring Data directly (less code, couples services to JPA); repository interfaces in `application/port/` (open question); full hexagonal `ports.in/out` (more ceremony).
- **Pros:** clear boundaries, verified by ArchUnit and Modulith.
- **Cons:** an entity, a Spring Data interface and a mapper per aggregate.
- **Scalability:** a repository implementation can change technology without touching services; a module can be carved out with its package and schema.

## ADR-05 In-process async work with the Modulith event publication registry

- **Proposal:** `@ApplicationModuleListener` for work after commit; the JPA event publication registry keeps unfinished events and resubmits them on restart.
- **Why:** the triggering change and its event commit atomically, without a broker.
- **Alternatives:** `@Async` without a registry (events lost on crash); a PostgreSQL job table; Kafka.
- **Pros:** reliable enough for housekeeping and moderate background work; no new infrastructure.
- **Cons:** at-least-once delivery, so listeners must be idempotent; work runs on the instance that committed.
- **Scalability:** externalise events to a broker later (Modulith supports it) without changing publishers.

## ADR-06 One response envelope for every REST endpoint

- **Proposal:** `ApiResponse<T>` and `ApiErrorResponse` (api-standards.md §3) everywhere, including security errors.
- **Why:** one parsing path in the frontend; every response carries a correlation ID and timestamp.
- **Alternatives:** the seed document's variant (`traceId`, `items` inside `data`); RFC 9457 Problem Details (open question).
- **Pros:** consistent and self-describing.
- **Cons:** not an RFC standard; redundant with the HTTP status.
- **Scalability:** additive fields only; breaking changes via `/api/v2`.

## ADR-07 Live status over STOMP with PostgreSQL LISTEN/NOTIFY fan-out

- **Proposal:** status changes update `common.operation` and `pg_notify` in one transaction; every instance LISTENs and pushes to its STOMP subscribers; snapshot plus resync for recovery.
- **Why:** push without polling, no sticky sessions, no extra infrastructure, only committed state is pushed.
- **Alternatives:** SSE (no auth header from `EventSource`); a STOMP broker relay; polling.
- **Pros:** real-time, order-tolerant (versioned).
- **Cons:** one extra DB connection per instance; 8 KB NOTIFY payload limit; the load balancer must allow WebSocket.
- **Scalability:** move to a broker relay for user-targeted queues or high volume.

## ADR-08 OIDC resource server with in-memory bearer tokens

- **Proposal:** any OIDC provider; RS256 JWTs validated via JWKS; SPA login with code + PKCE; tokens in browser memory; roles `USER`/`ADMIN` from a configurable claim.
- **Why:** no password handling in the app; stateless API; the seed document asks to avoid `localStorage` for tokens.
- **Alternatives:** a backend-for-frontend with HttpOnly cookies (preferred by the seed document where feasible; needs CSRF protection and a session store); self-issued JWTs with local users.
- **Pros:** standard, stateless, provider-agnostic.
- **Cons:** tokens lost on reload need silent renew; XSS exposure lasts the life of a page (mitigated by CSP).
- **Scalability:** adding roles only changes claim mapping and `@PreAuthorize`.

## ADR-09 Flyway with timestamped versions; migrate on startup locally, one-off run elsewhere

- **Proposal:** per-module migration folders; `V<yyyyMMddHHmm>__<module>_…`; Flyway on startup in `local`/`test`; profile `migrate` as a one-off run in shared environments; the app connects with DML-only credentials there.
- **Why:** easy local start; least privilege and no concurrent migrations in shared environments; no version collisions between branches.
- **Alternatives:** migrate on every instance start everywhere; Liquibase; sequential `V1__`.
- **Pros:** deterministic; fix-forward recovery.
- **Cons:** one extra deploy step; timestamps are less readable.
- **Scalability:** a carved-out module takes its folder with it.

## ADR-10 Auditing base classes and explicit soft delete

- **Proposal:** `AuditableEntity` / `SoftDeletableEntity`; `AuditorAware` from system scope → JWT subject → anonymous; UTC `Clock`; soft delete via explicit `markDeleted`, `@SQLRestriction` per entity, native restore.
- **Why:** consistent auditing; `@SoftDelete` cannot store `deleted_by`; the seed document forbids silent global filters.
- **Alternatives:** Envers; DB triggers; an embeddable audit block.
- **Pros:** automatic and testable.
- **Cons:** inheritance coupling; partial unique indexes; native restore SQL.
- **Scalability:** Envers or an audit trail can be added per module.

## ADR-11 Angular Material (M3) themed from SCSS tokens

- **Proposal:** Material 21 used directly, themed via `mat.theme()` from tokens; wrappers only when they add behaviour.
- **Why:** accessible, maintained components; one source for brand values.
- **Alternatives:** custom kit on CDK; third-party kits.
- **Pros:** speed, accessibility, consistent theming.
- **Cons:** Material's look is customised through tokens only.
- **Scalability:** a custom design system later touches only tokens and `shared/ui`.

## ADR-12 SonarQube quality gate on every PR

- **Proposal:** Sonar analysis for Java and TypeScript with a new-code gate; skipped visibly when no server is configured.
- **Why:** one place for bugs, vulnerabilities, smells, duplication and coverage.
- **Alternatives:** CodeQL only; linters only.
- **Pros:** enforceable thresholds on new code.
- **Cons:** needs a server or cloud plan.
- **Scalability:** applies to new modules automatically.

## ADR-13 S3-compatible object storage behind a port

- **Proposal:** `ArtifactStorage` port with an S3 adapter (AWS SDK v2, endpoint configurable); metadata rows; backend-only writes; 5-minute presigned GET for downloads.
- **Why:** files do not belong in PostgreSQL; S3 is the de-facto API, served locally by MinIO.
- **Alternatives:** `bytea` columns; streaming downloads through the API; browser presigned uploads.
- **Pros:** no AWS types outside `infrastructure`; works with any S3-compatible store.
- **Cons:** two writes (row + object) need the PENDING/STORED sequence and a cleanup job.
- **Scalability:** storage scales independently; key prefixes per module.

## ADR-14 Independent backend and frontend CI

- **Proposal:** one path-filtered `ci.yml` with backend and frontend jobs and an always-run `ci-gate` required check; separate Sonar projects; additive `/api/v1` changes.
- **Why:** the seed document requires independent build and test commands; a change on one side should not wait for the other.
- **Alternatives:** one pipeline for both; separate repositories.
- **Pros:** faster feedback; independent releases later.
- **Cons:** API compatibility discipline.
- **Scalability:** each new deployable adds its own filter and job.

## ADR-15 Gradle with Kotlin DSL for the backend build

- **Proposal:** `backend/` is one Gradle project with Kotlin DSL build scripts, a `gradle/libs.versions.toml` version catalog and a committed wrapper with `distributionSha256Sum`. Java toolchain 21; `test` and `integrationTest` tasks; Spotless, JaCoCo and Sonar Gradle plugins.
- **Why:** user decision (13 Nov 2026), superseding the Maven wrapper.
- **Alternatives:** Maven wrapper (the seed document and the original spec); Gradle with Groovy DSL.
- **Pros:** incremental tasks and build cache; type-checked build scripts with IDE completion; one catalog for every pinned version; the Boot plugin provides `bootJar` and `bootRun`.
- **Cons:** build logic is code and can grow into custom tasks; no Enforcer-style dependency-convergence rule out of the box; the Maven build from story 1 must be migrated.
- **Scalability:** a carved-out module becomes a subproject; shared build logic moves to convention plugins only then.

## Decisions taken without an ADR

UUIDv7 IDs; schema per module; no cross-schema FKs; singular table names with plural REST paths; constructor injection; no Lombok or MapStruct by default; typed Reactive Forms; Vitest; Testcontainers 2; no root build orchestrator.
