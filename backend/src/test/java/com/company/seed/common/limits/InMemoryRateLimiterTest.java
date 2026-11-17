package com.company.seed.common.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.time.MutableClock;
import com.company.seed.common.error.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.unit.DataSize;

/** The token-bucket maths of {@link InMemoryRateLimiter}, its {@code Retry-After} and eviction. */
class InMemoryRateLimiterTest {

  private static final Instant START = Instant.parse("2026-11-16T09:00:00Z");
  private static final String A = "user:a";
  private static final String B = "user:b";

  private final MutableClock clock = new MutableClock(START);

  private InMemoryRateLimiter limiter(int writesPerMinute) {
    return new InMemoryRateLimiter(
        new LimitsProperties(DataSize.ofMegabytes(1), writesPerMinute), clock);
  }

  /** Takes {@code count} tokens, failing the test if any is refused. */
  private static void take(InMemoryRateLimiter limiter, String key, int count) {
    IntStream.range(0, count)
        .forEach(i -> assertThat(limiter.tryAcquire(key)).as("write %d", i + 1).isEmpty());
  }

  private static long retryAfter(Optional<Duration> wait) {
    assertThat(wait).as("refused").isPresent();
    return RateLimitExceededException.retryAfterSeconds(wait.get());
  }

  @Test
  void aNewCallerHasTheFullBudgetAtOnce() {
    InMemoryRateLimiter limiter = limiter(60);

    take(limiter, A, 60);

    assertThat(limiter.tryAcquire(A)).contains(Duration.ofSeconds(1));
  }

  @Test
  void aRefusedRequestTakesNoToken() {
    InMemoryRateLimiter limiter = limiter(60);
    take(limiter, A, 60);

    limiter.tryAcquire(A);
    limiter.tryAcquire(A);
    clock.advance(Duration.ofSeconds(1));

    assertThat(limiter.tryAcquire(A)).isEmpty();
    assertThat(limiter.tryAcquire(A)).contains(Duration.ofSeconds(1));
  }

  @Test
  void tokensRefillContinuously() {
    InMemoryRateLimiter limiter = limiter(60);
    take(limiter, A, 60);

    clock.advance(Duration.ofMillis(500));
    assertThat(limiter.tryAcquire(A)).contains(Duration.ofMillis(500));
    clock.advance(Duration.ofMillis(500));
    assertThat(limiter.tryAcquire(A)).isEmpty();
    clock.advance(Duration.ofSeconds(10));
    take(limiter, A, 10);
    assertThat(limiter.tryAcquire(A)).contains(Duration.ofSeconds(1));
  }

  @Test
  void theBudgetNeverExceedsTheCapacity() {
    InMemoryRateLimiter limiter = limiter(60);
    take(limiter, A, 1);

    clock.advance(Duration.ofMinutes(10));

    take(limiter, A, 60);
    assertThat(limiter.tryAcquire(A)).isPresent();
  }

  @Test
  void callersHaveSeparateBudgets() {
    InMemoryRateLimiter limiter = limiter(5);
    take(limiter, A, 5);

    assertThat(limiter.tryAcquire(A)).isPresent();
    take(limiter, B, 5);
  }

  @ParameterizedTest(
      name = "5/min, next write {0} ms after the 5th -> wait {1} ms, Retry-After {2}")
  @CsvSource({
    // Retry-After is the wait rounded up to a whole second.
    "0,    12000, 12",
    "500,  11500, 12",
    "1000, 11000, 11",
    "1001, 10999, 11",
    "11999,    1,  1",
  })
  void retryAfterIsTheWaitForOneTokenRoundedUpToAWholeSecond(
      long afterMillis, long waitMillis, long retryAfterSeconds) {
    InMemoryRateLimiter limiter = limiter(5);
    take(limiter, A, 5);

    clock.advance(Duration.ofMillis(afterMillis));
    Optional<Duration> wait = limiter.tryAcquire(A);

    assertThat(wait).isPresent();
    assertThat(wait.get().toMillis()).isEqualTo(waitMillis);
    assertThat(retryAfter(wait)).isEqualTo(retryAfterSeconds);
  }

  @Test
  void aWaitOfAFractionOfASecondIsRetryAfterOne() {
    InMemoryRateLimiter limiter = limiter(600);
    take(limiter, A, 600);

    Optional<Duration> wait = limiter.tryAcquire(A);

    assertThat(wait).contains(Duration.ofMillis(100));
    assertThat(retryAfter(wait)).isEqualTo(1);
  }

  @Test
  void anUnevenRateStillRefillsTheWholeBudgetInAMinute() {
    InMemoryRateLimiter limiter = limiter(7);
    take(limiter, A, 7);

    Optional<Duration> wait = limiter.tryAcquire(A);
    clock.advance(Duration.ofMinutes(1));

    assertThat(wait).isPresent();
    assertThat(retryAfter(wait)).isEqualTo(9); // 60 s / 7 = 8.57 s
    take(limiter, A, 7);
    assertThat(limiter.tryAcquire(A)).isPresent();
  }

  @Test
  void aClockThatGoesBackAddsNothing() {
    InMemoryRateLimiter limiter = limiter(60);
    take(limiter, A, 60);

    clock.set(START.minusSeconds(30));
    assertThat(limiter.tryAcquire(A)).isPresent();
    clock.set(START.plusSeconds(1));
    assertThat(limiter.tryAcquire(A)).isEmpty();
  }

  @Test
  void aBucketIdleForMoreThanAMinuteIsEvictedAndStartsFullAgain() {
    InMemoryRateLimiter limiter = limiter(5);
    take(limiter, A, 5);
    clock.advance(Duration.ofSeconds(30));
    take(limiter, B, 1);

    clock.advance(Duration.ofSeconds(31));
    limiter.evictIdle();

    assertThat(limiter.size()).as("only B, touched 31 s ago, is kept").isEqualTo(1);
    take(limiter, A, 5);
    assertThat(limiter.tryAcquire(A)).isPresent();
  }

  @Test
  void aBucketIdleForExactlyOneMinuteIsKept() {
    InMemoryRateLimiter limiter = limiter(5);
    take(limiter, A, 1);

    clock.advance(Duration.ofMinutes(1));
    limiter.evictIdle();

    assertThat(limiter.size()).isEqualTo(1);
  }

  @Test
  void aRefusedRequestKeepsItsBucketAlive() {
    InMemoryRateLimiter limiter = limiter(1);
    take(limiter, A, 1);
    clock.advance(Duration.ofSeconds(50));
    assertThat(limiter.tryAcquire(A)).isPresent();

    clock.advance(Duration.ofSeconds(50));
    limiter.evictIdle();

    assertThat(limiter.size()).isEqualTo(1);
  }

  @Test
  void theLargestBudgetDoesNotOverflow() {
    InMemoryRateLimiter limiter = limiter(Integer.MAX_VALUE);
    take(limiter, A, 1_000);

    clock.advance(Duration.ofHours(1));
    take(limiter, A, 1_000);

    assertThat(limiter.tryAcquire(A)).isEmpty();
  }
}
