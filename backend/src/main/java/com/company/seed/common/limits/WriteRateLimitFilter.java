package com.company.seed.common.limits;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.RateLimitExceededException;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.logging.LogSafe;
import com.company.seed.common.web.WriteMethods;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The per-caller write rate limit, {@code seed.limits.writes-per-minute} (security.md §6). Every
 * {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} takes one token from its caller's
 * budget ({@link CurrentActor#id()}, through the {@link RateLimiter}); other methods are never
 * limited. Over budget, the request is answered 429 {@code RATE_LIMITED} with {@code Retry-After}
 * (whole seconds until one token is available, rounded up) and logged at INFO.
 *
 * <p>Runs after Spring Security's filter chain ({@code -100}), so the caller is known, and before
 * {@code IdempotencyFilter} ({@code -90}), so a replayed keyed write still counts. A body rejected
 * by its {@code Content-Length} ({@link RequestSizeFilter}) never reaches this filter and costs no
 * token; a body sent without a length is only found too large while it is read, after this filter,
 * so it costs one.
 */
@Component
@Order(WriteRateLimitFilter.ORDER)
public class WriteRateLimitFilter extends OncePerRequestFilter {

  /** Filter order: after Spring Security's chain ({@code -100}), before the idempotency filter. */
  public static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 5;

  private static final Logger LOG = LoggerFactory.getLogger(WriteRateLimitFilter.class);

  private final RateLimiter rateLimiter;
  private final CurrentActor currentActor;
  private final ApiErrorResponseWriter errorWriter;

  public WriteRateLimitFilter(
      RateLimiter rateLimiter, CurrentActor currentActor, ApiErrorResponseWriter errorWriter) {
    this.rateLimiter = rateLimiter;
    this.currentActor = currentActor;
    this.errorWriter = errorWriter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !WriteMethods.ALL.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String caller = currentActor.id();
    Optional<Duration> wait = rateLimiter.tryAcquire(caller);
    if (wait.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    long retryAfter = RateLimitExceededException.retryAfterSeconds(wait.get());
    LOG.info(
        "{} {} rejected: write rate limit reached for {}; retry after {} s",
        LogSafe.clean(request.getMethod()),
        LogSafe.clean(request.getRequestURI()),
        LogSafe.clean(caller),
        retryAfter);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
    errorWriter.write(
        request,
        response,
        RateLimitExceededException.STATUS,
        ErrorCode.RATE_LIMITED,
        RateLimitExceededException.SAFE_MESSAGE);
  }
}
