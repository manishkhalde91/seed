package com.company.seed.common.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Writes one INFO summary line per request (errors-and-logging.md §4): method, route template (or
 * {@value #UNMATCHED}), the status the client received and the duration in milliseconds. No bodies,
 * headers or query strings; {@code /actuator/**} is skipped. The same fields are attached as
 * structured key-values ({@code http.request.method}, {@code http.route}, {@code
 * http.response.status_code} and {@code event.duration} in nanoseconds, as in ECS).
 *
 * <p>The line is written exactly once, always with the original request's route (never {@code
 * /error}) and the final status:
 *
 * <ul>
 *   <li>normally by the original dispatch, when the chain returns;
 *   <li>when an exception escapes the chain, by the ERROR re-dispatch that answers it;
 *   <li>when async processing has started, by the final ASYNC dispatch (or an ERROR dispatch, if
 *       that one fails).
 * </ul>
 *
 * Runs right after {@link CorrelationIdFilter}, so the line carries the correlation ID.
 */
@Component
@Order(RequestLoggingFilter.ORDER)
public class RequestLoggingFilter extends OncePerRequestFilter {

  /** Filter order: right after {@link CorrelationIdFilter}. */
  public static final int ORDER = CorrelationIdFilter.ORDER + 1;

  /** Route logged when no handler method matched the request. */
  public static final String UNMATCHED = "UNMATCHED";

  private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

  private static final String PREFIX = RequestLoggingFilter.class.getName();
  private static final String START_ATTRIBUTE = PREFIX + ".start";
  private static final String ROUTE_ATTRIBUTE = PREFIX + ".route";
  private static final String LOGGED_ATTRIBUTE = PREFIX + ".logged";
  private static final String ACTUATOR = "/actuator";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getDispatcherType() == DispatcherType.REQUEST) {
      originalDispatch(request, response, chain);
    } else {
      reDispatch(request, response, chain);
    }
  }

  private void originalDispatch(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isActuator(request)) {
      chain.doFilter(request, response);
      return;
    }
    request.setAttribute(START_ATTRIBUTE, System.nanoTime());
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      request.setAttribute(ROUTE_ATTRIBUTE, routeOf(request, response));
      if (completed && !isAsyncStarted(request)) {
        logOnce(request, response);
      }
    }
  }

  /**
   * An ERROR or ASYNC dispatch: logs if the original dispatch deferred the line (an escaped
   * exception, or async processing). Requests the original dispatch skipped have no start time.
   */
  private void reDispatch(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      if (completed && !isAsyncStarted(request)) {
        logOnce(request, response);
      }
    }
  }

  private static void logOnce(HttpServletRequest request, HttpServletResponse response) {
    if (request.getAttribute(LOGGED_ATTRIBUTE) != null
        || !(request.getAttribute(START_ATTRIBUTE) instanceof Long start)) {
      return;
    }
    request.setAttribute(LOGGED_ATTRIBUTE, Boolean.TRUE);
    long durationNanos = System.nanoTime() - start;
    String method = LogSafe.clean(request.getMethod());
    Object route = request.getAttribute(ROUTE_ATTRIBUTE);
    int status = response.getStatus();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
    LOG.atInfo()
        .addKeyValue("http.request.method", method)
        .addKeyValue("http.route", route)
        .addKeyValue("http.response.status_code", status)
        .addKeyValue("event.duration", durationNanos)
        .log("{} {} -> {} in {} ms", method, route, status, durationMs);
  }

  /**
   * The matched route template. A 404 answered by something other than a handler method (the
   * static-resource fallback {@code /**}, or no handler at all) is {@value #UNMATCHED}.
   */
  private static String routeOf(HttpServletRequest request, HttpServletResponse response) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (!(pattern instanceof String route) || route.isEmpty()) {
      return UNMATCHED;
    }
    boolean handlerMethod =
        request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof HandlerMethod;
    if (!handlerMethod && response.getStatus() == HttpStatus.NOT_FOUND.value()) {
      return UNMATCHED;
    }
    return route;
  }

  /**
   * Decided on the container-normalised path, not the raw URI: {@code /actuator/..;/probe/ok}
   * reaches {@code /probe/ok} and must be logged.
   */
  private static boolean isActuator(HttpServletRequest request) {
    String path =
        request.getPathInfo() == null
            ? request.getServletPath()
            : request.getServletPath() + request.getPathInfo();
    return path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }
}
