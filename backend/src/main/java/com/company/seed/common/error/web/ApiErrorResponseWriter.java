package com.company.seed.common.error.web;

import com.company.seed.common.api.ApiErrorResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds every {@link ApiErrorResponse}: for {@link GlobalExceptionHandler} and {@link
 * JsonErrorController} as a {@link ResponseEntity}, and for servlet filters (which run outside
 * Spring MVC) written straight to the {@link HttpServletResponse}. {@code timestamp} comes from the
 * injected {@link Clock}, {@code correlationId} from the MDC.
 */
@Component
public class ApiErrorResponseWriter {

  private final Clock clock;
  private final JsonMapper jsonMapper;

  public ApiErrorResponseWriter(Clock clock, JsonMapper jsonMapper) {
    this.clock = clock;
    this.jsonMapper = jsonMapper;
  }

  public ApiErrorResponse build(
      int status, String errorCode, String message, List<FieldError> errors, String path) {
    return new ApiErrorResponse(
        false,
        status,
        message,
        errorCode,
        errors,
        clock.instant(),
        path,
        MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY));
  }

  /**
   * The error as a JSON response entity. The content type is fixed, so the body is written as JSON
   * whatever the request's {@code Accept} header says.
   */
  public ResponseEntity<ApiErrorResponse> entity(
      int status,
      String errorCode,
      String message,
      List<FieldError> errors,
      String path,
      HttpHeaders headers) {
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(build(status, errorCode, message, errors, path));
  }

  /**
   * Writes the catalogue's error code and safe message for a bare {@code status} (such as the 401
   * and 403 of the security filter chain) to a raw response. Does nothing once committed.
   */
  public void write(HttpServletRequest request, HttpServletResponse response, int status)
      throws IOException {
    StatusMapping mapping = StatusMapping.of(status);
    write(request, response, status, mapping.errorCode(), mapping.message());
  }

  /** Writes the error to a raw response, for servlet filters. Does nothing once committed. */
  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String errorCode,
      String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.resetBuffer();
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    jsonMapper.writeValue(
        response.getOutputStream(),
        build(status, errorCode, message, List.of(), request.getRequestURI()));
    response.flushBuffer();
  }
}
