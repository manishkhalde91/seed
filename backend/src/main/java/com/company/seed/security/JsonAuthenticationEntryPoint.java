package com.company.seed.security;

import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.error.web.GlobalExceptionHandler;
import com.company.seed.common.error.web.SecurityErrorLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a request the filter chain could not authenticate (no token, or a token that failed a
 * check) with the 401 {@code UNAUTHENTICATED} envelope and {@code WWW-Authenticate: Bearer}. The
 * answer never names the reason and never echoes the token; the log line (INFO) names only the
 * exception type.
 *
 * <p>When the token could not be checked at all ({@link AuthenticationServiceException}: the
 * issuer's metadata or keys could not be fetched), the answer is 503 {@code SERVICE_UNAVAILABLE},
 * logged at ERROR with the stack trace.
 */
final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final Logger LOG = LoggerFactory.getLogger(JsonAuthenticationEntryPoint.class);

  private final ApiErrorResponseWriter writer;

  JsonAuthenticationEntryPoint(ApiErrorResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    if (authException instanceof AuthenticationServiceException) {
      int status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
      SecurityErrorLog.log(
          LOG, Level.ERROR, request, status, ErrorCode.SERVICE_UNAVAILABLE, authException);
      writer.write(request, response, status);
      return;
    }
    int status = HttpServletResponse.SC_UNAUTHORIZED;
    SecurityErrorLog.log(
        LOG, Level.INFO, request, status, ErrorCode.UNAUTHENTICATED, authException);
    if (!response.isCommitted()) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, GlobalExceptionHandler.BEARER_CHALLENGE);
    }
    writer.write(request, response, status);
  }
}
