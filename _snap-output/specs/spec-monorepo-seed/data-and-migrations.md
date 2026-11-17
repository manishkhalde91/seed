# Database, auditing, soft delete and Flyway

## 1. Schema strategy and ownership

- One PostgreSQL 16 database (`seed_db` locally), with **one schema per module** (`sample`, …) plus `common`.
- `common` owns `event_publication` (Modulith registry), `operation`, `artifact`, `idempotency_record` and `flyway_schema_history`.
- A module's repositories (backend-architecture.md §5) are internal to it. Other modules read its data only through its facade or its events.
- Foreign keys are allowed **within** a schema only. Across schemas, refer by UUID and name the column `<entity>_id`.
- Every table is schema-qualified in JPA (`@Table(schema = "sample")`) and in SQL; `search_path` is not relied on.
- `spring.jpa.hibernate.ddl-auto=validate` everywhere. Hibernate never changes the schema.

## 2. Identifiers

| Table kind | PK | Why |
|---|---|---|
| Exposed records | `uuid` holding **UUIDv7**, generated in the app (`IdGenerator`) | Safe to expose; time-ordered for B-tree inserts; known before insert |
| Internal tables (`idempotency_record`) | `bigint GENERATED ALWAYS AS IDENTITY` | Compact; never exposed |

Natural keys get a `UNIQUE` constraint (partial when the table is soft-deletable).

## 3. Naming conventions

| Object | Convention | Example |
|---|---|---|
| Table | singular snake_case | `sample_record` |
| Column | snake_case; FK `<ref>_id`; booleans `is_`/`has_` | `display_name`, `owner_id` |
| Primary key | `id`; constraint `<table>_pkey` (PG default) | |
| Foreign key | `fk_<table>__<ref_table>` | `fk_sample_note__sample_record` |
| Unique | `uq_<table>__<cols>` | `uq_sample_record__name` |
| Index | `ix_<table>__<cols>[__<suffix>]` | `ix_sample_record__created_at` |
| Check | `ck_<table>__<rule>` | `ck_sample_record__name_not_blank` |
| Flyway migration | `V<yyyyMMddHHmm>__<module>_<verb>_<object>.sql` | `V202611101005__sample_create_sample_record.sql` |

Singular table names and plural REST paths are the convention (seed §9.1). Status columns are `text` with a `CHECK` constraint rather than a PG enum.

## 4. Audit columns

Every exposed, mutable table has:

| Column | Type | Null | Filled by |
|---|---|---|---|
| `created_at` | `timestamptz` | no | `@CreatedDate` |
| `created_by` | `text` | no | `@CreatedBy` |
| `updated_at` | `timestamptz` | no; set equal to `created_at` on insert | `@LastModifiedDate` |
| `updated_by` | `text` | no; set on insert | `@LastModifiedBy` |
| `version` | `bigint` | no, default 0 | `@Version` |
| `deleted_at` | `timestamptz` | yes | explicit soft delete |
| `deleted_by` | `text` | yes | explicit soft delete |

Internal tables keep `created_at` (and `updated_at` where they change) only. Audit fields are never part of a request DTO.

### Base classes

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {
  @CreatedDate      @Column(nullable = false, updatable = false) private Instant createdAt;
  @CreatedBy        @Column(nullable = false, updatable = false) private String createdBy;
  @LastModifiedDate @Column(nullable = false)                    private Instant updatedAt;
  @LastModifiedBy   @Column(nullable = false)                    private String updatedBy;
  @Version                                                       private Long version; // null until the first insert
}

@MappedSuperclass
public abstract class SoftDeletableEntity extends AuditableEntity {
  private Instant deletedAt;
  private String deletedBy;
  public void markDeleted(Instant at, String by) { this.deletedAt = at; this.deletedBy = by; }
  public void restore() { this.deletedAt = null; this.deletedBy = null; }
  public boolean isDeleted() { return deletedAt != null; }
}
```

The ID lives on the concrete entity (`@Id UUID id`), not on the base class.

### Auditing configuration

- `common.auditing.JpaAuditingConfig`: `@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")`.
- `auditingDateTimeProvider` returns `Instant.now(clock)` from the injected UTC `Clock`, so tests control time.
- `hibernate.jdbc.time_zone=UTC`; the JVM runs with `-Duser.timezone=UTC`; Java types are `Instant`.

### AuditorAware resolution order

1. An open **system actor scope** (`SystemActor.run("system:<name>", …)`), opened by scheduled jobs and async listeners that act on their own.
2. An authenticated JWT: the `sub` claim, prefixed `user:`.
3. Otherwise `anonymous` (public endpoints normally do not write).

An async listener acting on behalf of a user takes the user from the event (`requestedBy`) and opens a scope for that user.

## 5. Soft delete

- Applies to `SoftDeletableEntity` subclasses only; not every table is soft-deletable.
- Each soft-deletable entity declares `@SQLRestriction("deleted_at IS NULL")` explicitly, and its `*PersistenceIT` tests that deleted rows are hidden. There is no global filter.
- Deletion is a domain operation: the application service calls `aggregate.markDeleted(clock.instant(), currentActor.id())` and `repository.save(aggregate)`. The repository interface has no `delete`, and the Spring Data interface declares no `delete*`.
- Unique constraints are partial: `CREATE UNIQUE INDEX uq_sample_record__name ON sample.sample_record (name) WHERE deleted_at IS NULL;`.
- **Restore** is `restore(id)` on the repository, implemented as a native update, because `@SQLRestriction` hides the row from JPQL. The sample exposes `POST /api/v1/sample-records/{id}/restore` to `ADMIN`.
- Hard purge is not part of the seed.

## 6. Optimistic locking

- `@Version` on every mutable aggregate. Update requests carry `version`; the application service compares it with the loaded aggregate.
- A conflict maps to `409 CONCURRENT_MODIFICATION`.
- Status changes that race (the operation table) use conditional SQL (`UPDATE … WHERE id = :id AND status_version = :expected`).

## 7. Transactions

- `@Transactional` sits on application services only; repositories join it. Read-only use cases use `readOnly = true`.
- `spring.jpa.open-in-view=false`.
- One use case = one transaction. A change and the events it publishes commit together (Modulith event publication registry).
- No external call (S3, HTTP) inside a transaction. An ArchUnit rule forbids `@Transactional` classes from calling ports marked `@External`.
- Default isolation is READ COMMITTED.

## 8. Indexing and queries

- Index every reference column used in lookups; partial indexes `… WHERE deleted_at IS NULL` on soft-deletable tables.
- Keyset pagination for unbounded tables; offset pagination up to about 10k rows.
- Sort fields are whitelisted per endpoint (api-standards.md §4).
- No N+1: fetch joins or entity graphs inside repository implementations.
- `hibernate.jdbc.batch_size=50`, `order_inserts=true`.

## 9. Connection pool (HikariCP)

| Setting | Value | Note |
|---|---|---|
| `maximum-pool-size` | 10 | Total = instances × pool + 1 LISTEN connection per instance; stay under `max_connections` minus 20% |
| `minimum-idle` | = max | Fixed-size pool |
| `connection-timeout` | 3 s | Fail fast |
| `max-lifetime` | 30 min | Below any proxy or DB timeout |
| `leak-detection-threshold` | 20 s | Logs WARN |
| Statement timeout | `-c statement_timeout=15s` | Via the JDBC URL |

The PG LISTEN connection is a dedicated connection opened outside Hikari.

## 10. Database roles

| Role | Privileges | Used by |
|---|---|---|
| `seed_owner` | owns the schemas; DDL | `migrate` run, and local/test startup |
| `seed_app` | `SELECT, INSERT, UPDATE, DELETE` on application tables | the running application in shared environments |

Grants are part of the migrations (`ALTER DEFAULT PRIVILEGES`). `infrastructure/postgres/` creates both roles locally.

## 11. Flyway standard

**Locations:** `classpath:db/migration/common`, then `classpath:db/migration/<module>`. One history table, `common.flyway_schema_history`. `spring.flyway.schemas` lists every schema.

**Naming and examples**

```
db/migration/common/V202611101000__common_initial_schema.sql
db/migration/sample/V202611101005__sample_create_sample_record.sql
db/migration/sample/V202611101010__sample_create_indexes.sql
db/migration/sample/R__sample_demo_data.sql          (repeatable, local/dev only via a location per profile)
```

**Rules**
1. Flyway is the only way the schema changes.
2. Never edit a migration applied to a shared environment; fix forward with a new one. CI runs `flyway validate` on a fresh database.
3. Migrations stay compatible with the previous release (expand/contract), so a rolling update can run both versions briefly.
4. Large index builds use `CREATE INDEX CONCURRENTLY` in their own migration (`-- flyway:executeInTransaction=false`).
5. Repeatable `R__` scripts only for views, functions and reference data, never for table changes.
6. `cleanDisabled=true` outside `test`; `validateOnMigrate=true`; `outOfOrder=false` in shared environments.
7. Review each migration for data loss, lock duration and recovery.

**Execution**
- `local` and `test`: Flyway runs on startup as `seed_owner`.
- `dev`, `qa`, `prod`: the image runs once with profile `migrate` before the new version starts; the application itself has Flyway disabled and connects as `seed_app`.
- A `SchemaVersionReadinessIndicator` reports readiness DOWN if the schema is behind the migrations on the classpath.
- Migration tests run against PostgreSQL (Testcontainers), never an in-memory database.

**Recovery.** Forward-only; no undo scripts. Take a snapshot before migrating a shared environment. `flyway repair` only after a documented manual clean-up.

### Example — sample table

```sql
-- V202611101005__sample_create_sample_record.sql
CREATE TABLE sample.sample_record (
  id          uuid        PRIMARY KEY,
  name        text        NOT NULL,
  description text,
  version     bigint      NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL,
  created_by  text        NOT NULL,
  updated_at  timestamptz NOT NULL,
  updated_by  text        NOT NULL,
  deleted_at  timestamptz,
  deleted_by  text,
  CONSTRAINT ck_sample_record__name_not_blank CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX uq_sample_record__name ON sample.sample_record (name) WHERE deleted_at IS NULL;
CREATE INDEX ix_sample_record__created_at ON sample.sample_record (created_at);
```
