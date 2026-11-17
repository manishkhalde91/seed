package com.company.seed.common.error.web;

import com.company.seed.common.api.ApiErrorResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.RateLimitExceededException;
import com.company.seed.common.error.SeedException;
import com.company.seed.common.limits.PayloadTooLargeException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring Boot's {@code BasicErrorController}: anything dispatched to {@code /error} (a
 * servlet-level failure, a filter calling {@code sendError}) gets the same JSON error envelope as
 * the {@link GlobalExceptionHandler}, with the original status and the original URI as {@code
 * path}. A {@link SeedException} thrown by a filter keeps its own status, code and safe message (a
 * {@link RateLimitExceededException} with its {@code Retry-After}); a {@link
 * PayloadTooLargeException} anywhere in the cause chain (a body read past the limit by a filter) is
 * 413 {@code PAYLOAD_TOO_LARGE}. A direct request to {@code /error} is answered as an unknown
 * route.
 *
 * <p>Open to everyone at the method level: an error dispatch answers whatever request failed,
 * anonymous ones included (the filter chain permits only the container's ERROR dispatches; a direct
 * request to {@code /error} still needs a token).
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class JsonErrorController implements ErrorController {

  private static final Logger LOG = LoggerFactory.getLogger(JsonErrorController.class);

  private final ApiErrorResponseWriter writer;

  public JsonErrorController(ApiErrorResponseWriter writer) {
    this.writer = writer;
  }

  @RequestMapping
  @PreAuthorize("permitAll()")
  public ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
    String path = originalPath(request);
    if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
      LOG.debug("Direct request to {} -> 404 {}", path, ErrorCode.NOT_FOUND);
      return writer.entity(
          404, ErrorCode.NOT_FOUND, SafeMessages.NOT_FOUND, List.of(), path, HttpHeaders.EMPTY);
    }
    Throwable exception = exceptionOf(request);
    if (Causes.findCause(exception, PayloadTooLargeException.class).orElse(null)
        instanceof PayloadTooLargeException tooLarge) {
      int tooLargeStatus = HttpStatus.CONTENT_TOO_LARGE.value();
      log(Level.INFO, tooLargeStatus, ErrorCode.PAYLOAD_TOO_LARGE, path, exception);
      return writer.entity(
          tooLargeStatus,
          ErrorCode.PAYLOAD_TOO_LARGE,
          tooLarge.getSafeMessage(),
          List.of(),
          path,
          HttpHeaders.EMPTY);
    }
    if (Causes.findCause(exception, SeedException.class).orElse(null)
        instanceof SeedException seed) {
      log(
          GlobalExceptionHandler.levelOf(seed),
          seed.getHttpStatus(),
          seed.getErrorCode(),
          path,
          seed);
      return writer.entity(
          seed.getHttpStatus(),
          seed.getErrorCode(),
          seed.getSafeMessage(),
          List.of(),
          path,
          seed instanceof RateLimitExceededException rateLimited
              ? GlobalExceptionHandler.retryAfter(rateLimited)
              : HttpHeaders.EMPTY);
    }
    int responseStatus = status >= 400 && status <= 599 ? status : 500;
    StatusMapping mapping = StatusMapping.of(responseStatus);
    log(mapping.level(), responseStatus, mapping.errorCode(), path, exception);
    return writer.entity(
        responseStatus, mapping.errorCode(), mapping.message(), List.of(), path, HttpHeaders.EMPTY);
  }

  private static String originalPath(HttpServletRequest request) {
    return request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
        ? uri
        : request.getRequestURI();
  }

  private static Throwable exceptionOf(HttpServletRequest request) {
    return request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable ex
        ? ex
        : null;
  }

  /** One line per dispatch; only ERROR carries the stack trace. */
  private static void log(Level level, int status, String errorCode, String path, Throwable ex) {
    String correlationId = MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY);
    if (level == Level.ERROR && ex != null) {
      LOG.error(
          "Error dispatch for {} -> {} {} [correlationId={}]",
          path,
          status,
          errorCode,
          correlationId,
          ex);
      return;
    }
    LOG.atLevel(level)
        .log(
            "Error dispatch for {} -> {} {} [correlationId={}]",
            path,
            status,
            errorCode,
            correlationId);
  }
}
