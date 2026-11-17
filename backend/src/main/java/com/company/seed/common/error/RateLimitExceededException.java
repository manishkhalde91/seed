package com.company.seed.common.error;

import java.time.Duration;
import java.util.Map;

/**
 * 429 {@link ErrorCode#RATE_LIMITED}: the caller has used up a budget and may retry after {@link
 * #getRetryAfter()}. The write rate limit answers it from its filter; a service with a budget of
 * its own throws this. {@code GlobalExceptionHandler} sends {@code Retry-After} as {@link
 * #retryAfterSeconds(Duration)}.
 */
public class RateLimitExceededException extends SeedException {

  public static final int STATUS = 429;

  /** The client-safe message of every 429. */
  public static final String SAFE_MESSAGE = "Too many requests";

  private final Duration retryAfter;

  /**
   * @param retryAfter how long until the caller may try again; not negative
   */
  public RateLimitExceededException(Duration retryAfter) {
    this(retryAfter, Map.of());
  }

  public RateLimitExceededException(Duration retryAfter, Map<String, Object> details) {
    super(ErrorCode.RATE_LIMITED, STATUS, SAFE_MESSAGE, details, true, null);
    if (retryAfter == null || retryAfter.isNegative()) {
      throw new IllegalArgumentException("retryAfter must be zero or positive");
    }
    this.retryAfter = retryAfter;
  }

  public Duration getRetryAfter() {
    return retryAfter;
  }

  /**
   * The {@code Retry-After} value for a wait: whole seconds, rounded up (a client that waits that
   * long finds a token), and at least 1.
   */
  public static long retryAfterSeconds(Duration wait) {
    long seconds = wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0);
    return Math.max(1, seconds);
  }
}
