package com.company.seed.security;

import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.logging.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Spring's CORS checks, with a refused request (an origin, method or header outside the rules)
 * answered by the 403 {@code FORBIDDEN} envelope instead of the plain-text {@code Invalid CORS
 * request}. Logged at INFO: a refused origin is a browser's question, not an attack on its own.
 */
final class JsonCorsProcessor extends DefaultCorsProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(JsonCorsProcessor.class);

  private final ApiErrorResponseWriter writer;

  JsonCorsProcessor(ApiErrorResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public boolean processRequest(
      CorsConfiguration config, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (super.processRequest(config, request, response)) {
      return true;
    }
    LOG.info(
        "{} {} -> 403 {} (CORS request refused for origin {})",
        LogSafe.clean(request.getMethod()),
        LogSafe.clean(request.getRequestURI()),
        ErrorCode.FORBIDDEN,
        LogSafe.clean(request.getHeader(HttpHeaders.ORIGIN)));
    writer.write(request, response, HttpServletResponse.SC_FORBIDDEN);
    return false;
  }

  /** {@link #processRequest} writes the envelope; nothing is written here. */
  @Override
  protected void rejectRequest(ServerHttpResponse response) {
    // The envelope is written by processRequest, which has the request for the path.
  }
}
