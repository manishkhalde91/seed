package com.company.seed.common.limits;

import java.time.Duration;
import java.util.Optional;

/**
 * The write budget of each caller (security.md §6). The seed's implementation, {@link
 * InMemoryRateLimiter}, counts on each instance separately; a shared store would be another
 * implementation of this interface.
 */
public interface RateLimiter {

  /**
   * Takes one token from {@code key}'s budget.
   *
   * @return empty when the token was taken (the request may proceed); otherwise how long until one
   *     token is available, with nothing taken
   */
  Optional<Duration> tryAcquire(String key);
}
