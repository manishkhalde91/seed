package com.company.fixtures.web;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.api.PageResult;
import com.company.seed.common.error.BusinessRuleException;
import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.ExternalServiceException;
import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.error.RateLimitExceededException;
import com.company.seed.common.error.ResourceNotFoundException;
import com.company.seed.common.limits.PayloadTooLargeException;
import com.company.seed.common.validation.OnCreate;
import com.company.seed.common.validation.OnUpdate;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Test-only endpoints that drive every row of the envelope and error matrix. {@code @Validated}, so
 * path and query constraints fail with {@code ConstraintViolationException}.
 */
@RestController
@Validated
@RequestMapping("/probe")
public class ProbeController {

  public static final String SECRET_SQL = "select * from secret_table";

  private final ApiResponses responses;

  public ProbeController(ApiResponses responses) {
    this.responses = responses;
  }

  public record Thing(String id, String name) {}

  /** Query parameters bound as an object, as list endpoints do. */
  public record Filter(int count) {}

  @GetMapping("/ok")
  public ResponseEntity<ApiResponse<Thing>> ok() {
    return responses.ok("Thing retrieved", new Thing("1", "first"));
  }

  @PostMapping("/created")
  public ResponseEntity<ApiResponse<Thing>> created() {
    return responses.created("Thing created", new Thing("2", "second"), URI.create("/probe/2"));
  }

  @PostMapping("/accepted")
  public ResponseEntity<ApiResponse<Map<String, String>>> accepted() {
    return responses.accepted(
        "Export started",
        Map.of("operationId", "op-1", "status", "QUEUED"),
        URI.create("/api/v1/operations/op-1"));
  }

  @DeleteMapping("/things/{id}")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
    return responses.deleted("Thing deleted");
  }

  @GetMapping("/page")
  public ResponseEntity<ApiResponse<List<Thing>>> page() {
    return responses.page(
        "Things retrieved",
        new PageResult<>(List.of(new Thing("1", "first"), new Thing("2", "second")), 0, 2, 5));
  }

  @PostMapping("/items")
  public ResponseEntity<ApiResponse<ProbeRequest>> create(
      @Validated(OnCreate.class) @RequestBody ProbeRequest request) {
    return responses.created("Item created", request, URI.create("/probe/items/1"));
  }

  @PutMapping("/items/{id}")
  public ResponseEntity<ApiResponse<ProbeRequest>> update(
      @PathVariable long id, @Validated(OnUpdate.class) @RequestBody ProbeRequest request) {
    return responses.ok("Item updated", request);
  }

  @GetMapping("/items/{id}")
  public ResponseEntity<ApiResponse<Long>> find(@PathVariable @Min(1) long id) {
    return responses.ok("Item retrieved", id);
  }

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<Integer>> search(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return responses.ok("Searched", size);
  }

  @GetMapping("/count")
  public ResponseEntity<ApiResponse<Integer>> count(@RequestParam int count) {
    return responses.ok("Counted", count);
  }

  @GetMapping("/header")
  public ResponseEntity<ApiResponse<String>> header(@RequestHeader("X-Probe") String probe) {
    return responses.ok("Header read", probe);
  }

  @GetMapping("/filter")
  public ResponseEntity<ApiResponse<Integer>> filter(@ModelAttribute Filter filter) {
    return responses.ok("Filtered", filter.count());
  }

  /** Async processing: the response is written by the ASYNC re-dispatch. */
  @GetMapping("/async/{outcome}")
  public Callable<ResponseEntity<ApiResponse<Thing>>> async(@PathVariable String outcome) {
    return () -> {
      if (outcome.equals("conflict")) {
        throw new ConflictException("THING_ALREADY_EXISTS", "Thing already exists");
      }
      return responses.created("Thing created", new Thing("3", "async"), URI.create("/probe/3"));
    };
  }

  @GetMapping("/throw/{kind}")
  public ResponseEntity<ApiResponse<Void>> fail(@PathVariable String kind) {
    throw switch (kind) {
      case "not-found" -> new ResourceNotFoundException("THING_NOT_FOUND", "Thing not found");
      case "conflict" -> new ConflictException("THING_ALREADY_EXISTS", "Thing already exists");
      case "business" -> new BusinessRuleException("THING_LIMIT_REACHED", "Thing limit reached");
      case "forbidden" -> new ForbiddenOperationException("Not your thing");
      case "bad-gateway" -> external(502, ErrorCode.EXTERNAL_SERVICE_ERROR);
      case "unavailable" -> external(503, ErrorCode.SERVICE_UNAVAILABLE);
      case "timeout" -> external(504, ErrorCode.EXTERNAL_SERVICE_TIMEOUT);
      case "too-large" -> new MaxUploadSizeExceededException(1024);
      case "body-too-large-io" -> new UncheckedIOException(new PayloadTooLargeException("1MB"));
      case "body-too-large-json" ->
          new HttpMessageNotReadableException(
              "JSON parse error",
              new UncheckedIOException(new PayloadTooLargeException("1MB")),
              new MockHttpInputMessage(new byte[0]));
      case "rate-limited" -> new RateLimitExceededException(Duration.ofSeconds(7));
      case "rate-limited-fraction" -> new RateLimitExceededException(Duration.ofMillis(6_001));
      case "db-locked" -> new ObjectOptimisticLockingFailureException(Thing.class, "1");
      case "db-connection" ->
          new CannotGetJdbcConnectionException(
              "Failed to obtain JDBC Connection", new SQLException(SECRET_SQL));
      case "db-transient" -> new QueryTimeoutException(SECRET_SQL);
      case "db-other" -> new InvalidDataAccessResourceUsageException(SECRET_SQL);
      case "db-recoverable" -> new RecoverableDataAccessException(SECRET_SQL);
      case "tx-unavailable" ->
          new CannotCreateTransactionException(
              "Could not open JPA EntityManager for transaction", new SQLException(SECRET_SQL));
      case "violations-empty" -> new ConstraintViolationException(SECRET_SQL, Set.of());
      case "violations-internal" -> internalViolation();
      case "access-denied" -> new AccessDeniedException(SECRET_SQL);
      case "authorization-denied" ->
          new AuthorizationDeniedException(SECRET_SQL, new AuthorizationDecision(false));
      case "unauthenticated" -> new InsufficientAuthenticationException(SECRET_SQL);
      default -> new IllegalStateException(SECRET_SQL);
    };
  }

  /** The body limit hit while a handler reads the body itself: the exception unwrapped. */
  @PostMapping("/body-too-large")
  public ResponseEntity<ApiResponse<Void>> bodyTooLarge() throws IOException {
    throw new PayloadTooLargeException("2KB");
  }

  /** Bean Validation of an object that is not a controller, as a service or JPA would do it. */
  private static ConstraintViolationException internalViolation() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      ProbeRequest invalid = new ProbeRequest(null, "", null, null, "short-secret", null, null);
      return new ConstraintViolationException(validator.validate(invalid));
    }
  }

  private static ExternalServiceException external(int status, String errorCode) {
    return new ExternalServiceException(
        "thing-store",
        status,
        errorCode,
        "Thing store failed",
        true,
        new SocketTimeoutException("read timed out"));
  }
}
