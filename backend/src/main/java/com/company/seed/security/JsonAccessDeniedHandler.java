package com.company.seed.security;

import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.error.web.SecurityErrorLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers an authenticated request the filter chain's rules refused (such as {@code
 * /actuator/loggers} without {@code ADMIN}) with the 403 {@code FORBIDDEN} envelope; logged at WARN
 * with the subject and the path. A {@code @PreAuthorize} refusal inside a handler is answered by
 * {@code GlobalExceptionHandler} the same way.
 */
final class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private static final Logger LOG = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

  private final ApiErrorResponseWriter writer;

  JsonAccessDeniedHandler(ApiErrorResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    int status = HttpServletResponse.SC_FORBIDDEN;
    SecurityErrorLog.log(
        LOG, Level.WARN, request, status, ErrorCode.FORBIDDEN, accessDeniedException);
    writer.write(request, response, status);
  }
}
