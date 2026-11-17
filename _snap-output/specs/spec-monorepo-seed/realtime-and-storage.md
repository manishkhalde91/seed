# Live operation status and object storage

Two generic platform features in `common` that any feature can use. The `sample` export demonstrates both (architecture-diagrams.md §3).

## 1. Live operation status (CAP-10)

### 1.1 Model

`common.operation`:

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` (UUIDv7) | returned as `operationId` |
| `kind` | `text` | e.g. `SAMPLE_EXPORT`; one value per use case |
| `subject_id` | `uuid` null | the record the work is about, by ID |
| `requested_by` | `text` | actor that started it; used for read checks |
| `status` | `text` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` (state machine in architecture-diagrams.md §4) |
| `status_version` | `smallint` | starts at 1; +1 on every change |
| `result` | `jsonb` null | small result, e.g. `{ "artifactId": "…" }` |
| `error_code`, `error_message` | `text` null | safe values only |
| `correlation_id` | `text` | from the starting request |
| audit columns | | `created_*`, `updated_*` |

### 1.2 Flow

1. A feature's application service starts work in its own transaction: `OperationService.start(kind, subjectId)` inserts `QUEUED`, and the service publishes an event (e.g. `SampleExportRequested`). Both commit together. The controller returns 202.
2. An `@ApplicationModuleListener` handles the event after commit, async, in a new transaction. The Modulith event publication registry keeps unfinished events and resubmits them after a restart.
3. Each status change goes through `OperationService` (`markRunning`, `complete(result)`, `fail(code, message)`): a conditional `UPDATE … SET status = :to, status_version = status_version + 1 WHERE id = :id AND status_version = :expected`, plus `pg_notify('operation_status', json)` in the same transaction. The payload stays under 8,000 bytes.
4. Every instance runs `PgStatusListener`: a dedicated non-pooled connection doing `LISTEN operation_status`. It forwards each notification to `/topic/operations/{id}`. If the connection drops, it reconnects with 1–30 s backoff and publishes on `/topic/system/resync`.
5. The client subscribes to the topic first, then requests the snapshot, and keeps the highest `version` it has seen.

No external call runs inside the status transactions. A listener that calls S3 or HTTP does so between `markRunning` and `complete`.

### 1.3 Rules

- STOMP uses the simple in-memory broker; NOTIFY gives cross-instance fan-out without sticky sessions or extra infrastructure.
- Subscriptions are authorised: the subscriber must be the `requested_by` actor or `ADMIN`.
- Outbound limits: inbound frames 16 KB, `sendTimeLimit` 15 s, `sendBufferSizeLimit` 512 KB; sessions close when the JWT expires (checked every 30 s) and on shutdown (code 1012).
- Operations older than 7 days are deleted by the `operation-cleanup` job.
- The load balancer must forward the `Upgrade` and `Connection` headers and allow an idle timeout of at least 60 s.

## 2. Object storage (CAP-11)

### 2.1 Shape

| Aspect | Rule |
|---|---|
| Who writes | The backend only, through the `ArtifactStorage` port. Browsers never upload directly to storage. |
| Who reads | The backend through the port; browsers through a presigned GET URL issued by the API. |
| Port | `common.storage.application.port.ArtifactStorage`: `put(key, InputStream, length, contentType) → StoredObject`, `open(key)`, `presignGet(key, Duration) → URL`, `delete(key)`. No AWS types in the signature. |
| Adapters | `S3ArtifactStorage` (AWS SDK v2 `S3Client` + `S3Presigner`; endpoint and path-style configurable for MinIO or any S3-compatible store); `InMemoryArtifactStorage` for unit tests. |
| Metadata | `common.artifact`: `id` (UUIDv7), `module`, `subject_id`, `kind`, `object_key`, `content_type`, `size_bytes`, `sha256`, `status` (`PENDING`, `STORED`), `owner`, audit columns. Behind `ArtifactRepository`. |
| Object key | `<module>/<subjectId or none>/<kind>/<artifactId>`. No user input in keys. |
| Facade | `common.storage.ArtifactService`: `store(...) → ArtifactRef`, `downloadUrl(artifactId) → {url, expiresAt}`. Features call the facade, never the port. |

### 2.2 Write sequence (no storage call inside a DB transaction)

1. Insert the `artifact` row as `PENDING` in its own transaction.
2. PUT the object outside any transaction, with a checksum; compute `sha256`.
3. Update the row to `STORED` with size and hash (conditional on `PENDING`).
4. Job `artifact-orphan-cleanup` (hourly) deletes `PENDING` rows older than 1 h and their objects.

### 2.3 Read

- `GET /api/v1/artifacts/{id}/download-url` (owner or `ADMIN`) returns `{ url, expiresAt }`; TTL 5 minutes (`seed.storage.presign-ttl`).
- Only `STORED` artifacts are served; otherwise 404 `ARTIFACT_NOT_FOUND`.
- URLs force `response-content-disposition=attachment`.

### 2.4 Bucket and access

| Control | Setting |
|---|---|
| Bucket | One per environment, name from `${STORAGE_BUCKET}` |
| Public access | Blocked; TLS-only bucket policy in shared environments |
| Encryption | Server-side encryption on |
| Credentials | Runtime identity of the hosting platform in shared environments; static keys only for local MinIO |
| Local | `minio` in `infrastructure/compose.yaml`; bucket created at startup |

### 2.5 Failures

- Storage errors map to `ExternalServiceException` (retryable for 5xx/throttling, not for 4xx).
- Storage is not part of readiness. Metrics: `seed.storage.put` / `get` timers and error counters.

## 3. Scheduled jobs

There is no worker process. The few housekeeping jobs run inside the application with `@Scheduled`, wrapped by `common.jobs.ScheduledJob`:
1. Take `pg_try_advisory_lock(hashtext('job:'||name))` on a dedicated connection. If it is busy, skip the run (another instance has it).
2. Open a system-actor scope (`system:job:<name>`) and a correlation ID.
3. Run, then log start and end at INFO and record `seed_job_duration_seconds{job,status}`.

| Job | Schedule | Purpose |
|---|---|---|
| `idempotency-cleanup` | hourly | Delete idempotency records older than 24 h |
| `artifact-orphan-cleanup` | hourly | Delete stale `PENDING` artifacts and their objects |
| `operation-cleanup` | daily | Delete operations older than 7 days |
| `event-publication-cleanup` | daily | Delete completed Modulith event publications older than 7 days |
