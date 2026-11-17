package com.company.fixtures.web;

import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Test-only servlet filter producing failures outside Spring MVC: an exception and a {@code
 * sendError} (both dispatched to {@code /error}), and an error written by {@link
 * ApiErrorResponseWriter}.
 */
public class ProbeFilter extends OncePerRequestFilter {

  public static final String THROWS = "/probe-filter/throws";
  public static final String SEND_ERROR = "/probe-filter/send-error";
  public static final String WRITER = "/probe-filter/writer";
  public static final String SEED = "/probe-filter/seed";

  private final ApiErrorResponseWriter writer;

  public ProbeFilter(ApiErrorResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    switch (request.getRequestURI()) {
      case THROWS -> throw new IllegalStateException(ProbeController.SECRET_SQL);
      case SEND_ERROR -> response.sendError(HttpServletResponse.SC_FORBIDDEN, "filter says no");
      case SEED -> throw new ConflictException("THING_ALREADY_EXISTS", "Thing already exists");
      case WRITER ->
          writer.write(request, response, 429, ErrorCode.RATE_LIMITED, "Too many requests");
      default -> chain.doFilter(request, response);
    }
  }
}
