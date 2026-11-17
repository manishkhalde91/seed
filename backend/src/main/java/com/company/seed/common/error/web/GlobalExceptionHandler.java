package com.company.seed.common.error.web;

import com.company.seed.common.api.ApiErrorResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.api.FieldError;
import com.company.seed.common.error.BusinessRuleException;
import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ConstraintErrorMapping;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.ExternalServiceException;
import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.error.RateLimitExceededException;
import com.company.seed.common.error.ResourceNotFoundException;
import com.company.seed.common.error.SeedException;
import com.company.seed.common.limits.LimitsProperties;
import com.company.seed.common.limits.PayloadTooLargeException;
import com.company.seed.common.logging.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.ClassUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single exception-to-response mapping of the REST API (errors-and-logging.md §2). Controllers
 * never catch exceptions themselves. Every exception is logged here, once, at the level of the
 * catalogue; the response {@code message} is always a fixed, safe text (for a {@link
 * SeedException}, its {@code safeMessage}), never the exception's own message.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** The {@code WWW-Authenticate} value of every 401: the scheme only, never a reason. */
  public static final String BEARER_CHALLENGE = "Bearer";

  private final ApiErrorResponseWriter writer;
  private final Map<String, String> errorCodesByConstraint;
  private final String uploadTooLargeMessage;

  /**
   * @param constraintMappings every {@link ConstraintErrorMapping} bean; two that map the same
   *     constraint name fail startup
   * @param maxFileSize {@code spring.servlet.multipart.max-file-size}, named in the upload 413
   * @param maxRequestSize {@code spring.servlet.multipart.max-request-size}, likewise
   */
  public GlobalExceptionHandler(
      ApiErrorResponseWriter writer,
      ObjectProvider<ConstraintErrorMapping> constraintMappings,
      @Value("${spring.servlet.multipart.max-file-size:1MB}") String maxFileSize,
      @Value("${spring.servlet.multipart.max-request-size:10MB}") String maxRequestSize) {
    this.writer = writer;
    this.errorCodesByConstraint = indexByConstraint(constraintMappings.orderedStream().toList());
    this.uploadTooLargeMessage = uploadTooLargeMessage(maxFileSize, maxRequestSize);
  }

  /** "Upload exceeds 10MB": the smaller of the two limits that are set ({@code -1} is none). */
  static String uploadTooLargeMessage(String maxFileSize, String maxRequestSize) {
    return Stream.of(maxFileSize, maxRequestSize)
        .map(DataSize::parse)
        .filter(size -> size.toBytes() > 0)
        .min(Comparator.naturalOrder())
        .map(size -> "Upload exceeds " + LimitsProperties.text(size))
        .orElse(SafeMessages.PAYLOAD_TOO_LARGE);
  }

  static Map<String, String> indexByConstraint(List<ConstraintErrorMapping> mappings) {
    Map<String, String> index = new HashMap<>();
    for (ConstraintErrorMapping mapping : mappings) {
      mapping
          .errorCodes()
          .forEach(
              (constraint, code) -> {
                String previous =
                    index.putIfAbsent(ConstraintErrorMapping.normalize(constraint), code);
                if (previous != null) {
                  throw new IllegalStateException(
                      "Constraint "
                          + constraint
                          + " is mapped twice, to "
                          + previous
                          + " and to "
                          + code
                          + "; map each constraint in one ConstraintErrorMapping only");
                }
              });
    }
    return Map.copyOf(index);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> handleBodyValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    return validationFailed(ex, request, FieldErrorMapper.fromErrors(ex.getBindingResult()));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiErrorResponse> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    if (ex.isForReturnValue()) {
      // The controller returned an invalid value: a server bug, not a client error.
      return handleUnexpected(ex, request);
    }
    return validationFailed(ex, request, FieldErrorMapper.fromMethodValidation(ex));
  }

  /**
   * Bean Validation failed. Only a violation of a controller's own parameters ({@code @Validated}
   * controller) is the client's fault; one from a service or a JPA entity is a server bug, answered
   * with a plain 500 that echoes no field or value. (Hibernate's database constraint violation
   * arrives wrapped in a {@link DataIntegrityViolationException}.)
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    Set<ConstraintViolation<?>> violations =
        ex.getConstraintViolations() == null ? Set.of() : ex.getConstraintViolations();
    if (violations.isEmpty()
        || !violations.stream().allMatch(GlobalExceptionHandler::isOnController)) {
      return handleUnexpected(ex, request);
    }
    return validationFailed(ex, request, FieldErrorMapper.fromViolations(violations));
  }

  private static boolean isOnController(ConstraintViolation<?> violation) {
    Object root = violation.getRootBean();
    return root != null
        && AnnotatedElementUtils.hasAnnotation(ClassUtils.getUserClass(root), Controller.class);
  }

  /** A stale {@code @Version}: someone else changed the row since it was read. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ApiErrorResponse> handleOptimisticLock(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.INFO,
        HttpStatus.CONFLICT,
        ErrorCode.CONCURRENT_MODIFICATION,
        SafeMessages.CONCURRENT_MODIFICATION);
  }

  /**
   * A constraint mapped by a {@link ConstraintErrorMapping} is a conflict with existing data (409,
   * WARN); any other violation is a server bug (500, ERROR with the stack trace). The log line
   * names the constraint; the response never does.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiErrorResponse> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    String constraint = constraintName(ex);
    String mappedCode = mappedErrorCode(constraint);
    String detail = " constraint=" + (constraint == null ? "unknown" : LogSafe.clean(constraint));
    if (mappedCode != null) {
      return respond(
          ex,
          request,
          Level.WARN,
          HttpStatus.CONFLICT,
          mappedCode,
          SafeMessages.CONFLICTS_WITH_EXISTING_DATA,
          detail);
    }
    return respond(
        ex,
        request,
        Level.ERROR,
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.DATA_INTEGRITY_VIOLATION,
        SafeMessages.INTERNAL_ERROR,
        detail);
  }

  /**
   * No transaction could begin, typically because no connection could be obtained: retrying may
   * succeed once the database is back.
   */
  @ExceptionHandler(CannotCreateTransactionException.class)
  ResponseEntity<ApiErrorResponse> handleCannotCreateTransaction(
      CannotCreateTransactionException ex, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.ERROR,
        HttpStatus.SERVICE_UNAVAILABLE,
        ErrorCode.SERVICE_UNAVAILABLE,
        SafeMessages.SERVICE_UNAVAILABLE);
  }

  /**
   * Any other database failure: 503 when retrying may succeed (a transient or recoverable failure,
   * an unreachable database), otherwise 500. Both need a human, so both log at ERROR.
   */
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiErrorResponse> handleDataAccess(
      DataAccessException ex, HttpServletRequest request) {
    if (ex instanceof TransientDataAccessException
        || ex instanceof RecoverableDataAccessException
        || ex instanceof DataAccessResourceFailureException) {
      return respond(
          ex,
          request,
          Level.ERROR,
          HttpStatus.SERVICE_UNAVAILABLE,
          ErrorCode.SERVICE_UNAVAILABLE,
          SafeMessages.SERVICE_UNAVAILABLE);
    }
    return respond(
        ex,
        request,
        Level.ERROR,
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.INTERNAL_ERROR,
        SafeMessages.INTERNAL_ERROR);
  }

  /** The violated constraint as the database names it, from Hibernate's translated exception. */
  static String constraintName(Throwable ex) {
    return Causes.findCause(ex, org.hibernate.exception.ConstraintViolationException.class)
        .map(org.hibernate.exception.ConstraintViolationException::getConstraintName)
        .filter(name -> !name.isBlank())
        .map(name -> name.substring(name.lastIndexOf('.') + 1))
        .orElse(null);
  }

  /** The error code mapped to the violated constraint, or {@code null} when it is not mapped. */
  String mappedErrorCode(String constraint) {
    return constraint == null
        ? null
        : errorCodesByConstraint.get(ConstraintErrorMapping.normalize(constraint));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ApiErrorResponse> handleMalformed(Exception ex, HttpServletRequest request) {
    var tooLarge = Causes.findCause(ex, PayloadTooLargeException.class);
    if (tooLarge.isPresent()) {
      return payloadTooLarge(ex, tooLarge.get(), request);
    }
    return respond(
        ex,
        request,
        Level.DEBUG,
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        SafeMessages.MALFORMED_REQUEST);
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ApiErrorResponse> handleNoRoute(Exception ex, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.DEBUG,
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND,
        SafeMessages.NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    if (ex.getSupportedHttpMethods() != null) {
      headers.setAllow(ex.getSupportedHttpMethods());
    }
    log(
        ex,
        request,
        Level.DEBUG,
        HttpStatus.METHOD_NOT_ALLOWED.value(),
        ErrorCode.METHOD_NOT_ALLOWED);
    return writer.entity(
        HttpStatus.METHOD_NOT_ALLOWED.value(),
        ErrorCode.METHOD_NOT_ALLOWED,
        SafeMessages.METHOD_NOT_ALLOWED,
        List.of(),
        request.getRequestURI(),
        headers);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.DEBUG,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        SafeMessages.UNSUPPORTED_MEDIA_TYPE);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiErrorResponse> handlePayloadTooLarge(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.INFO,
        HttpStatus.CONTENT_TOO_LARGE,
        ErrorCode.PAYLOAD_TOO_LARGE,
        uploadTooLargeMessage);
  }

  /**
   * The request body grew past {@code seed.limits.max-body-size} while it was read. Usually it
   * arrives wrapped (a JSON parse error, an I/O failure); {@link #handleMalformed} and {@link
   * #handleUnexpected} look for it in the cause chain.
   */
  @ExceptionHandler(PayloadTooLargeException.class)
  ResponseEntity<ApiErrorResponse> handleBodyTooLarge(
      PayloadTooLargeException ex, HttpServletRequest request) {
    return payloadTooLarge(ex, ex, request);
  }

  private ResponseEntity<ApiErrorResponse> payloadTooLarge(
      Exception ex, PayloadTooLargeException tooLarge, HttpServletRequest request) {
    return respond(
        ex,
        request,
        Level.INFO,
        HttpStatus.CONTENT_TOO_LARGE,
        ErrorCode.PAYLOAD_TOO_LARGE,
        tooLarge.getSafeMessage());
  }

  /** A budget is used up: 429 with {@code Retry-After} in whole seconds, rounded up. */
  @ExceptionHandler(RateLimitExceededException.class)
  ResponseEntity<ApiErrorResponse> handleRateLimited(
      RateLimitExceededException ex, HttpServletRequest request) {
    log(ex, request, Level.INFO, ex.getHttpStatus(), ex.getErrorCode());
    return writer.entity(
        ex.getHttpStatus(),
        ex.getErrorCode(),
        ex.getSafeMessage(),
        List.of(),
        request.getRequestURI(),
        retryAfter(ex));
  }

  /** The {@code Retry-After} header of a {@link RateLimitExceededException}. */
  static HttpHeaders retryAfter(RateLimitExceededException ex) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(
        HttpHeaders.RETRY_AFTER,
        Long.toString(RateLimitExceededException.retryAfterSeconds(ex.getRetryAfter())));
    return headers;
  }

  /**
   * Method security ({@code @PreAuthorize}) denied the call: {@link AccessDeniedException} and its
   * {@code AuthorizationDeniedException}. For an authenticated caller that is 403 (WARN, with the
   * subject and the path); for nobody (anonymous, or no security context) it is the missing
   * authentication, answered like {@link #handleAuthentication}.
   */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    if (!SecurityErrorLog.isAuthenticated()) {
      return unauthenticated(ex, request);
    }
    int status = HttpStatus.FORBIDDEN.value();
    SecurityErrorLog.log(LOG, Level.WARN, request, status, ErrorCode.FORBIDDEN, ex);
    return writer.entity(
        status,
        ErrorCode.FORBIDDEN,
        SafeMessages.FORBIDDEN,
        List.of(),
        request.getRequestURI(),
        HttpHeaders.EMPTY);
  }

  /**
   * Authentication failed inside a handler. The answer names no reason and echoes no credential;
   * like the filter chain's 401, it carries {@code WWW-Authenticate: Bearer}.
   */
  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiErrorResponse> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    return unauthenticated(ex, request);
  }

  private ResponseEntity<ApiErrorResponse> unauthenticated(
      Exception ex, HttpServletRequest request) {
    int status = HttpStatus.UNAUTHORIZED.value();
    SecurityErrorLog.log(LOG, Level.INFO, request, status, ErrorCode.UNAUTHENTICATED, ex);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
    return writer.entity(
        status,
        ErrorCode.UNAUTHENTICATED,
        SafeMessages.UNAUTHENTICATED,
        List.of(),
        request.getRequestURI(),
        headers);
  }

  @ExceptionHandler(SeedException.class)
  ResponseEntity<ApiErrorResponse> handleSeedException(
      SeedException ex, HttpServletRequest request) {
    log(ex, request, levelOf(ex), ex.getHttpStatus(), ex.getErrorCode());
    return writer.entity(
        ex.getHttpStatus(),
        ex.getErrorCode(),
        ex.getSafeMessage(),
        List.of(),
        request.getRequestURI(),
        HttpHeaders.EMPTY);
  }

  /** The client went away mid-response: nothing can be written, and nothing is wrong here. */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  void handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
    LOG.debug(
        "{} {} -> client disconnected ({}) [correlationId={}]",
        request.getMethod(),
        request.getRequestURI(),
        ex.getClass().getName(),
        MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY));
  }

  /**
   * The fallback. A Spring MVC exception outside the catalogue still carries its own status (406, a
   * missing header, {@code ResponseStatusException}) and is answered with it, not with a 500; one
   * caused by a {@link PayloadTooLargeException} (an I/O failure while reading the body) is 413.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    var tooLarge = Causes.findCause(ex, PayloadTooLargeException.class);
    if (tooLarge.isPresent()) {
      return payloadTooLarge(ex, tooLarge.get(), request);
    }
    if (ex instanceof ErrorResponse errorResponse
        && !(ex instanceof HandlerMethodValidationException)) {
      int status = errorResponse.getStatusCode().value();
      StatusMapping mapping = StatusMapping.of(status);
      log(ex, request, mapping.level(), status, mapping.errorCode());
      return writer.entity(
          status,
          mapping.errorCode(),
          mapping.message(),
          List.of(),
          request.getRequestURI(),
          HttpHeaders.EMPTY);
    }
    return respond(
        ex,
        request,
        Level.ERROR,
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.INTERNAL_ERROR,
        SafeMessages.INTERNAL_ERROR);
  }

  /** The log level of the catalogue (errors-and-logging.md §2) for one of the seed exceptions. */
  static Level levelOf(SeedException ex) {
    return switch (ex) {
      case ResourceNotFoundException notFound -> Level.DEBUG;
      case ConflictException conflict -> Level.INFO;
      case BusinessRuleException businessRule -> Level.INFO;
      case ForbiddenOperationException forbidden -> Level.WARN;
      case ExternalServiceException external -> Level.WARN;
      default -> ex.getHttpStatus() >= 500 ? Level.ERROR : Level.INFO;
    };
  }

  private ResponseEntity<ApiErrorResponse> validationFailed(
      Exception ex, HttpServletRequest request, List<FieldError> errors) {
    int status = HttpStatus.BAD_REQUEST.value();
    log(ex, request, Level.DEBUG, status, ErrorCode.VALIDATION_FAILED);
    return writer.entity(
        status,
        ErrorCode.VALIDATION_FAILED,
        SafeMessages.VALIDATION_FAILED,
        errors,
        request.getRequestURI(),
        HttpHeaders.EMPTY);
  }

  private ResponseEntity<ApiErrorResponse> respond(
      Exception ex,
      HttpServletRequest request,
      Level level,
      HttpStatus status,
      String errorCode,
      String message) {
    return respond(ex, request, level, status, errorCode, message, "");
  }

  private ResponseEntity<ApiErrorResponse> respond(
      Exception ex,
      HttpServletRequest request,
      Level level,
      HttpStatus status,
      String errorCode,
      String message,
      String detail) {
    log(ex, request, level, status.value(), errorCode, detail);
    return writer.entity(
        status.value(), errorCode, message, List.of(), request.getRequestURI(), HttpHeaders.EMPTY);
  }

  private static void log(
      Exception ex, HttpServletRequest request, Level level, int status, String errorCode) {
    log(ex, request, level, status, errorCode, "");
  }

  /**
   * The one log line per handled exception; only ERROR carries the stack trace. {@code detail} is a
   * safe, preformatted suffix (such as the violated constraint).
   */
  private static void log(
      Exception ex,
      HttpServletRequest request,
      Level level,
      int status,
      String errorCode,
      String detail) {
    String correlationId = MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY);
    if (level == Level.ERROR) {
      LOG.error(
          "{} {} -> {} {}{} [correlationId={}]",
          request.getMethod(),
          request.getRequestURI(),
          status,
          errorCode,
          detail,
          correlationId,
          ex);
      return;
    }
    StringBuilder extra = new StringBuilder(detail);
    if (ex instanceof ExternalServiceException external) {
      extra.append(" dependency=").append(external.getDependency());
      if (external.getCause() != null) {
        extra.append(" cause=").append(external.getCause());
      }
    }
    if (ex instanceof SeedException seed && !seed.getDetails().isEmpty()) {
      extra.append(" details=").append(seed.getDetails());
    }
    // Framework exception texts can echo request input (rejected values, JSON fragments), so only
    // the type is logged; a SeedException's message is its safe message.
    String exception = ex instanceof SeedException ? ex.toString() : ex.getClass().getName();
    LOG.atLevel(level)
        .log(
            "{} {} -> {} {}{} ({}) [correlationId={}]",
            request.getMethod(),
            request.getRequestURI(),
            status,
            errorCode,
            extra,
            exception,
            correlationId);
  }
}
