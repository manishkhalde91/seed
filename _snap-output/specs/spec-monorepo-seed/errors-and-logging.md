# Exception handling, validation and logging

## 1. Exception hierarchy (`common.error`)

```
SeedException (abstract, RuntimeException) — errorCode, httpStatus, safeMessage, details map, retryable flag
├─ BusinessRuleException        422  module-specific code; thrown by domain/application
├─ ResourceNotFoundException    404  <ENTITY>_NOT_FOUND
├─ ConflictException            409  <ENTITY>_ALREADY_EXISTS, INVALID_STATE_TRANSITION, IDEMPOTENCY_KEY_REUSED
├─ ForbiddenOperationException  403  ownership checks in application services
└─ ExternalServiceException     502/503/504; retryable unless the dependency returned 4xx
```

- Feature exceptions extend these (`SampleRecordNotFoundException extends ResourceNotFoundException`). Modules never invent new base classes.
- `common.error` has no Spring imports, so the domain layer can throw these exceptions.
- Infrastructure adapters translate vendor exceptions (S3, HTTP clients) into `ExternalServiceException`.

## 2. Global exception handler

`common.error.web.GlobalExceptionHandler` is the single `@RestControllerAdvice @Order(HIGHEST_PRECEDENCE)`. Controllers never catch exceptions themselves. Each mapping returns `ResponseEntity<ApiErrorResponse>`.

| Exception | Status | errorCode | Log |
|---|---|---|---|
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | 400 | `VALIDATION_FAILED` + `errors[]` | DEBUG |
| `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException` | 400 | `MALFORMED_REQUEST` | DEBUG |
| `ResourceNotFoundException`, `NoResourceFoundException` | 404 | own / `NOT_FOUND` | DEBUG |
| `HttpRequestMethodNotSupportedException` / `HttpMediaTypeNotSupportedException` | 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | DEBUG |
| `ConflictException` | 409 | own | INFO |
| `ObjectOptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` | INFO |
| `DataIntegrityViolationException` | 409 when the constraint is in the module's constraint→code map, else 500 | mapped / `DATA_INTEGRITY_VIOLATION` | WARN |
| `BusinessRuleException` | 422 | own | INFO |
| `AccessDeniedException`, `AuthorizationDeniedException`, `ForbiddenOperationException` | 403 | `FORBIDDEN` | WARN (subject, path) |
| `AuthenticationException` | 401 | `UNAUTHENTICATED` | INFO |
| `MaxUploadSizeExceededException` / size filter | 413 | `PAYLOAD_TOO_LARGE` | INFO |
| `RateLimitExceededException` | 429 | `RATE_LIMITED` | INFO |
| `ExternalServiceException` | 502/503/504 | own | WARN, with dependency name |
| `DataAccessException` (other) | 503 if transient, else 500 | `SERVICE_UNAVAILABLE` / `INTERNAL_ERROR` | ERROR + stack |
| `Exception` (fallback) | 500 | `INTERNAL_ERROR`; "Unexpected error. Quote the correlation ID." | ERROR + stack |

**Rules**
- Each exception is logged **once**, by the handler.
- Responses never contain library exception messages, SQL, class names or stack traces (`server.error.include-*=never`).
- 401/403 from the security filters, and errors from servlet filters, use the same `ApiErrorResponseWriter`. A replacement `ErrorController` covers anything reaching `/error`.
- A STOMP `@MessageExceptionHandler` maps the same exceptions to `ERROR` frames.
- **Async listeners** (e.g. the sample export) catch failures per unit of work: retryable failures are retried up to 3 times with backoff (Spring Framework 7 `@Retryable`); after that, the operation is marked `FAILED` with the error code and a safe message, and the failure is logged once at ERROR.

## 3. Input validation (Jakarta Bean Validation)

- Request DTOs carry the constraints. Controllers use `@Valid @RequestBody`; the controller class is `@Validated` so path and query constraints apply.
- Nested objects: `@Valid` on the field; collections: `List<@Valid ItemDto>`.
- Groups `OnCreate` / `OnUpdate` (in `common.validation`) apply only where create and update rules differ.
- **Rules that need state** (uniqueness, limits, transitions) live in `domain/` or the application service and throw `BusinessRuleException` / `ConflictException`. Uniqueness comes from the database constraint mapped to 409; validators never query the database.
- Domain value objects validate themselves in their constructors.
- Cross-field rules use a custom class-level constraint only when needed.

```java
public record CreateSampleRecordRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1000) String description) {}

@Target(TYPE) @Retention(RUNTIME) @Constraint(validatedBy = DateRangeValidator.class)
public @interface ValidDateRange {          // pattern for a cross-field rule
  String message() default "{validation.dateRange}";
  String start(); String end();
  Class<?>[] groups() default {}; Class<? extends Payload>[] payload() default {};
}
```

- Messages come from `ValidationMessages.properties`. `FieldError.code` is the constraint's simple name (`NotBlank`, `ValidDateRange`); the frontend keys its messages on it.
- Field paths are dotted with indices (`items[2].name`), matching the frontend `FormGroup` paths.
- **Frontend parity:** each constrained DTO has an entry in `docs/validation-rules.md` (field → constraint → frontend validator), and both sides test the same accept/reject samples.

## 4. Logging standards

**Format.** SLF4J with parameterised messages (no string concatenation). `logging.structured.format.console=ecs` (JSON) everywhere except `local`, which uses readable console output. Every line carries `@timestamp` (UTC), `log.level`, `log.logger`, `message`, `service.name`, `correlationId`, `traceId`/`spanId` when tracing is on, `user.id` (subject or `system:*`), and `operationId` when known.

| Level | Use |
|---|---|
| ERROR | Needs a human: unhandled 5xx, failed async work after retries, broken invariant |
| WARN | Degraded but handled: retry scheduled, slow or failing dependency, access denied |
| INFO | Lifecycle facts, once each: request summary, state change, job start/end |
| DEBUG | Diagnostics; off in shared environments; toggled per logger via `/actuator/loggers` (admin only) |
| TRACE | Never outside `local` |

**Correlation ID**
1. `CorrelationIdFilter` runs **before** the security filter chain. It reads or creates the ID, puts it in MDC and echoes it in the response header.
2. Events carry `correlationId`; the async executor's `TaskDecorator` copies MDC and the security context into listeners.
3. Scheduled jobs open a new ID per run (`job:<name>:<uuid>`).
4. STOMP sessions take the ID from CONNECT; pushes carry the ID of the change that caused them.
5. The Angular interceptor sends `X-Correlation-Id` on every request (frontend-architecture.md §6).

A correlation ID is not distributed tracing. Micrometer Tracing / OpenTelemetry export is enabled only when a trace backend exists (`management.otlp.tracing.endpoint`).

**Request logging.** One INFO line per request after completion: method, route template, status, duration. Bodies are not logged. Health probes are excluded.

**Masking and forbidden data**
- Never log passwords, tokens (`Authorization`, JWTs, refresh tokens), secrets, keys or full personal data.
- A `StructuredLoggingJsonMembersCustomizer` masks members named `password`, `secret`, `token`, `authorization`, `apiKey`, `privateKey`, `email`.
- `LogSafe.clean(value)` wraps caller-supplied values: control characters → `_` (CWE-117), truncated to 200 chars.
- A log-capture test sends a request with a bearer token and a password field and asserts that neither appears.

**Stack traces** are logged only at ERROR for unexpected exceptions, never for expected validation or business outcomes, and never returned to clients.
