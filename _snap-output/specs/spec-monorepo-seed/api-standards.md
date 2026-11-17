# API standards

## 1. URLs and methods

- Base path `/api/v1`. Collections are lowercase plural nouns, kebab-case when multi-word, nested at most one level: `/api/v1/sample-records/{id}`.
- Path IDs are UUIDv7 strings. Query parameters are camelCase.
- Non-CRUD actions are verb sub-resources: `POST /api/v1/sample-records/{id}/restore`.

| Method | Use | Success status |
|---|---|---|
| `GET` | read one or a list | 200 |
| `POST` | create | 201 + `Location` |
| `POST` | start long-running work | 202 + `Location: /api/v1/operations/{operationId}` |
| `PUT` | full replace (with `version`) | 200 |
| `PATCH` | partial update (JSON Merge Patch, with `version`) | 200 |
| `DELETE` | soft delete | 200, `data: null` |

- Timestamps are ISO-8601 UTC (`2026-11-11T08:00:00Z`).
- Every write accepts an optional `Idempotency-Key` header (1–64 chars `[A-Za-z0-9._:-]`). Same key and same body → the stored response is replayed; same key with a different body → `409 IDEMPOTENCY_KEY_REUSED`. Records are kept 24 h. Clients never retry a non-idempotent request without a key.
  - Only `POST`, `PUT`, `PATCH` and `DELETE` take part. Form (`application/x-www-form-urlencoded`) and `multipart/form-data` writes are not covered: they pass through untouched, key or not, and nothing is stored. An invalid key → `400 MALFORMED_REQUEST` ("Invalid Idempotency-Key header").
  - A keyed request body has the global body limit only (`seed.limits.max-body-size`, default 1 MB, security.md §6): a larger body → `413 PAYLOAD_TOO_LARGE`, before anything is stored. A keyed write, a replay included, counts against the caller's write rate (`429 RATE_LIMITED`). If the database cannot be reached while claiming the key → `503 SERVICE_UNAVAILABLE`.
  - "Same request" means the same method, raw request URI, raw query and body bytes. A replay repeats the stored status, `Content-Type`, `Location` and body (including the first request's `correlationId`) and adds `Idempotent-Replayed: true`; no other response header is stored or replayed. The `X-Correlation-Id` header is the current request's.
  - A duplicate that arrives while the first request is still running → `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` with `Retry-After: 1`. A claim older than `seed.idempotency.abandon-after` (default 5 min, longer than any write) is taken over by the next duplicate, so a write running longer than that can run twice.
  - 2xx and 4xx responses are stored. A 1xx, 3xx or 5xx response, an error or redirect answered by the servlet container (`sendError`, `sendRedirect`), or a response body larger than `seed.idempotency.max-stored-response-size` (default 1 MB; sent, but not stored) releases the key, so a retry runs again.

## 2. Status code map

| Status | When | `errorCode` |
|---|---|---|
| 400 | Bean Validation failure | `VALIDATION_FAILED` |
| 400 | malformed JSON, type mismatch, missing parameter | `MALFORMED_REQUEST` |
| 401 | missing, invalid or expired token | `UNAUTHENTICATED` |
| 403 | authenticated but not allowed | `FORBIDDEN` |
| 404 | resource missing or soft-deleted; unknown route | `<ENTITY>_NOT_FOUND`, `NOT_FOUND` |
| 405 / 415 | wrong method / content type | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` |
| 409 | unique conflict, optimistic lock, state conflict, idempotency key reuse, same idempotency key still in progress (with `Retry-After`) | `<ENTITY>_ALREADY_EXISTS`, `CONCURRENT_MODIFICATION`, `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| 413 | body over limit | `PAYLOAD_TOO_LARGE` |
| 422 | well-formed request that breaks a domain rule | `<MODULE>_<RULE>` |
| 429 | rate limit (with `Retry-After`) | `RATE_LIMITED` |
| 500 | unexpected | `INTERNAL_ERROR` |
| 502 / 503 / 504 | external dependency failed / unavailable / timed out | `EXTERNAL_SERVICE_ERROR`, `SERVICE_UNAVAILABLE`, `EXTERNAL_SERVICE_TIMEOUT` |

Error codes are `UPPER_SNAKE` and stable. A message may change; a code may not.

## 3. Response envelope

Two generic records in `common.api` are the only response wrappers in the codebase.

```java
public record ApiResponse<T>(boolean success, String message, T data,
                             Instant timestamp, String correlationId, PageMeta pagination) {}
public record PageMeta(int page, int size, long totalElements, int totalPages, boolean hasNext) {}

public record ApiErrorResponse(boolean success, int status, String message, String errorCode,
                               List<FieldError> errors, Instant timestamp, String path,
                               String correlationId) {}
public record FieldError(String field, String code, String message, Object rejectedValue) {}
```

- Controllers return `ResponseEntity<ApiResponse<T>>` built with `ApiResponses.ok(data)`, `.created(data, location)`, `.accepted(operation)`, `.page(page)` or `.deleted()`. `timestamp` and `correlationId` come from `Clock` and MDC, never by hand.
- `pagination` appears only on paged responses. `data: null` is always written.
- `rejectedValue` is omitted for fields marked `@Sensitive` and truncated to 100 chars.
- `ApiErrorResponse` is written by `GlobalExceptionHandler`, `JsonAuthenticationEntryPoint` and `JsonAccessDeniedHandler`, so security errors share the shape.
- `message` is safe to show; it never contains library exception text.
- An ArchUnit rule requires every `@RestController` method to return `ResponseEntity<ApiResponse<?>>`. Actuator and OpenAPI endpoints are exempt.
- The seed document's alternative (`traceId`, `items` inside `data`) and RFC 9457 are not used; switching is an open question. Two formats are never mixed.

### Samples

Create:
```http
POST /api/v1/sample-records
Authorization: Bearer eyJ…
X-Correlation-Id: 6f1c…
Content-Type: application/json

{ "name": "Example record", "description": "Seed validation example" }
```
```json
HTTP/1.1 201 Created
Location: /api/v1/sample-records/01934b6e-…

{ "success": true, "message": "Sample record created",
  "data": { "id": "01934b6e-…", "name": "Example record", "description": "Seed validation example", "version": 0 },
  "timestamp": "2026-11-11T08:00:00Z", "correlationId": "6f1c…" }
```

Long-running:
```json
HTTP/1.1 202 Accepted
Location: /api/v1/operations/01934b6f-…

{ "success": true, "message": "Export started",
  "data": { "operationId": "01934b6f-…", "status": "QUEUED", "version": 1,
            "statusTopic": "/topic/operations/01934b6f-…" },
  "timestamp": "…", "correlationId": "6f1c…" }
```

Paged list:
```json
{ "success": true, "message": "Records retrieved",
  "data": [ { "id": "…", "name": "Example record" } ],
  "pagination": { "page": 0, "size": 20, "totalElements": 57, "totalPages": 3, "hasNext": true },
  "timestamp": "…", "correlationId": "…" }
```

Validation error:
```json
HTTP/1.1 400 Bad Request
{ "success": false, "status": 400, "message": "Request validation failed",
  "errorCode": "VALIDATION_FAILED",
  "errors": [ { "field": "name", "code": "NotBlank", "message": "must not be blank", "rejectedValue": "" } ],
  "timestamp": "2026-11-11T08:00:00Z", "path": "/api/v1/sample-records", "correlationId": "6f1c…" }
```

## 4. Pagination, sorting and filtering

- `page` (0-based, default 0), `size` (default 20, max 100), `sort=field,asc|desc` (repeatable). Sort fields are **whitelisted** per endpoint; an unknown field returns 400 `VALIDATION_FAILED`.
- Filters are named query parameters; ranges use `From`/`To` suffixes.
- Controllers accept `PageQuery` plus a filter record; services return `PageResult<T>`. Spring Data `Page` stays inside repository implementations.
- Unbounded feeds use a cursor (`after=<id>`) and do not compute totals.

## 5. Request validation

See [errors-and-logging.md](errors-and-logging.md) §3: `@Valid` on `@RequestBody`, `@Validated` on the controller for path and query constraints, groups `OnCreate`/`OnUpdate` where rules differ.

## 6. Versioning

- URI versioning (`/api/v1`). Additive changes stay in v1; clients ignore unknown fields.
- A breaking change adds `/api/v2/...`; v1 is marked deprecated in OpenAPI and returns `Deprecation` and `Sunset` headers for at least one release.

## 7. OpenAPI

- springdoc generates the document from annotations on controllers and DTOs.
- An `OperationCustomizer` wraps every 2xx schema as `ApiResponse<T>` and adds `ApiErrorResponse` for 400/401/403/404/409/422/500.
- `./gradlew build` writes `backend/openapi/api.yaml`; CI fails on drift.
- Swagger UI is enabled in `local` and `dev` only (`SEED_API_DOCS_ENABLED`); disabled in `prod`.

## 8. Headers

| Header | Direction | Rule |
|---|---|---|
| `X-Correlation-Id` | in / out | Accepted if it matches `[A-Za-z0-9._:-]{1,64}`, otherwise replaced by a new UUIDv7. Echoed in the response header and envelope |
| `traceparent` | in / out | W3C trace context when tracing is enabled; independent of the correlation ID |
| `Idempotency-Key` | in | See §1 |
| `Idempotent-Replayed` | out | `true` on a response replayed for a repeated `Idempotency-Key` (§1); absent otherwise |

## 9. WebSocket / STOMP contract

| Frame / destination | Direction | Rule |
|---|---|---|
| `CONNECT` to `/ws` | client → server | `Authorization: Bearer <jwt>` header required; optional `X-Correlation-Id`; STOMP 1.2; heart-beat `10000,10000` |
| `SUBSCRIBE /topic/operations/{operationId}` | client → server | Caller must be allowed to read the operation; at most 50 subscriptions per session |
| `SUBSCRIBE /app/operations/{operationId}` | client → server | One-off snapshot, same payload as a push; send **after** the topic subscription |
| `SUBSCRIBE /topic/system/resync` | client → server | Server publishes `{"reason":"listener-reconnected"}` when it may have missed notifications |
| `SEND /app/**` | client → server | Rejected; the channel is push-only |
| `ERROR` | server → client | `unauthorized`, `forbidden-destination`, `token-expired`, `too-many-subscriptions`, `not-found`; connection closes |

Push payload (not enveloped):
```json
{ "operationId": "01934b6f-…", "kind": "SAMPLE_EXPORT", "subjectId": null,
  "status": "SUCCEEDED", "version": 3, "result": { "artifactId": "01934b70-…" },
  "errorCode": null, "errorMessage": null,
  "updatedAt": "2026-11-11T08:00:09Z", "correlationId": "6f1c…" }
```

`errorCode` and `errorMessage` are set only when `status` is `FAILED` (safe values, e.g. `OPERATION_FAILED`, "The operation failed"). The REST snapshot `GET /api/v1/operations/{id}` returns the same fields plus `createdAt` and `statusTopic`.

## 10. Endpoint catalogue (seed)

| Method & path | Role | Notes |
|---|---|---|
| `GET /actuator/health/**` | public (liveness/readiness only) | not enveloped |
| `GET /api/v1/me` | any authenticated | `{subject, name, roles}` |
| `GET /api/v1/sample-records` | `USER` | paged, sortable by `name`, `createdAt` — **demo** |
| `GET /api/v1/sample-records/{id}` | `USER` | **demo** |
| `POST /api/v1/sample-records` | `USER` | 201 — **demo** |
| `PUT /api/v1/sample-records/{id}` | `USER` | with `version` — **demo** |
| `DELETE /api/v1/sample-records/{id}` | `ADMIN` | soft delete — **demo** |
| `POST /api/v1/sample-records/{id}/restore` | `ADMIN` | **demo** |
| `POST /api/v1/sample-records/export` | `USER` | 202 + operation — **demo** |
| `GET /api/v1/operations/{id}` | owner or `ADMIN` | snapshot (REST fallback) |
| `GET /api/v1/artifacts/{id}/download-url` | owner or `ADMIN` | `{url, expiresAt}`, 5-minute presigned GET |
| `GET /ws` | JWT in CONNECT | WebSocket upgrade |

`ADMIN` implies `USER`.
