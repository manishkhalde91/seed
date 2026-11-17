package com.company.seed.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.GenericFilterBean;

/**
 * The browser rules of the application port (security.md §4, §6), as a servlet filter ahead of the
 * request limits and the security chain, so that every answer carries them, including the 413 of
 * {@code RequestSizeFilter}, the chain's 401 and 403, and the 429 of the write rate limit:
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}, {@code Referrer-Policy: no-referrer} and {@code
 *       Content-Security-Policy: frame-ancestors 'none'} on every dispatch;
 *   <li>CORS on the original request, as Spring's {@code CorsFilter} does it: a refused request or
 *       a preflight is answered here and goes no further.
 * </ul>
 *
 * The security chain still writes the same headers (on the management port, which gets only the
 * chain); a header already present is left alone.
 */
final class BrowserPolicyFilter extends GenericFilterBean {

  static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  static final String NOSNIFF = "nosniff";
  static final String REFERRER_POLICY = "Referrer-Policy";
  static final String NO_REFERRER = "no-referrer";
  static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  static final String FRAME_ANCESTORS_NONE = "frame-ancestors 'none'";

  private final CorsConfigurationSource corsConfigurationSource;
  private final CorsProcessor corsProcessor;

  BrowserPolicyFilter(
      CorsConfigurationSource corsConfigurationSource, CorsProcessor corsProcessor) {
    this.corsConfigurationSource = corsConfigurationSource;
    this.corsProcessor = corsProcessor;
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;
    response.setHeader(CONTENT_TYPE_OPTIONS, NOSNIFF);
    response.setHeader(REFERRER_POLICY, NO_REFERRER);
    response.setHeader(CONTENT_SECURITY_POLICY, FRAME_ANCESTORS_NONE);
    if (request.getDispatcherType() == DispatcherType.REQUEST) {
      CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
      if (!corsProcessor.processRequest(config, request, response)
          || CorsUtils.isPreFlightRequest(request)) {
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
