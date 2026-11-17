package com.company.seed.common.limits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A token bucket per key, kept in this instance's memory (security.md §6): each bucket holds up to
 * {@code seed.limits.writes-per-minute} tokens, starts full and refills continuously at that many
 * tokens per minute, timed by the application {@link Clock}. With N instances a caller may write up
 * to N times the limit.
 *
 * <p>Token levels are exact integers: one token is {@value #WINDOW_MICROS} units and every
 * microsecond adds {@code writes-per-minute} units, so a minute refills the whole budget and the
 * wait for the next token is exact to the microsecond.
 *
 * <p>A bucket untouched for longer than one refill window is full again, so dropping it loses
 * nothing: {@link #evictIdle()} does that every minute (scheduling is off in the {@code migrate}
 * run, which serves no requests).
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

  /** The refill window: a full budget per minute. */
  public static final Duration WINDOW = Duration.ofMinutes(1);

  /** One token, in level units: the window in microseconds. */
  static final long WINDOW_MICROS = 60_000_000L;

  private static final Logger LOG = LoggerFactory.getLogger(InMemoryRateLimiter.class);

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final long capacity;
  private final Clock clock;

  public InMemoryRateLimiter(LimitsProperties properties, Clock clock) {
    this.capacity = properties.writesPerMinute();
    this.clock = clock;
  }

  /**
   * A bucket's state. {@code level} is in units of 1/{@value #WINDOW_MICROS} token; {@code
   * refilledAt} is the instant {@code level} was computed for; {@code touchedAt} the last request.
   */
  private record Bucket(long level, Instant refilledAt, Instant touchedAt) {}

  @Override
  public Optional<Duration> tryAcquire(String key) {
    Instant now = clock.instant();
    AtomicLong waitMicros = new AtomicLong();
    buckets.compute(
        key,
        (k, bucket) -> {
          Bucket current = bucket == null ? full(now) : refill(bucket, now);
          if (current.level() >= WINDOW_MICROS) {
            waitMicros.set(0);
            return new Bucket(current.level() - WINDOW_MICROS, current.refilledAt(), now);
          }
          waitMicros.set(Math.ceilDiv(WINDOW_MICROS - current.level(), capacity));
          return new Bucket(current.level(), current.refilledAt(), now);
        });
    long wait = waitMicros.get();
    return wait == 0 ? Optional.empty() : Optional.of(Duration.of(wait, ChronoUnit.MICROS));
  }

  /** Drops every bucket untouched for longer than {@link #WINDOW}; such a bucket is full anyway. */
  @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
  public void evictIdle() {
    Instant idleBefore = clock.instant().minus(WINDOW);
    AtomicInteger evicted = new AtomicInteger();
    for (String key : buckets.keySet()) {
      buckets.computeIfPresent(
          key,
          (k, bucket) -> {
            if (bucket.touchedAt().isBefore(idleBefore)) {
              evicted.incrementAndGet();
              return null;
            }
            return bucket;
          });
    }
    LOG.debug("Rate limiter evicted {} idle bucket(s); {} remain", evicted.get(), buckets.size());
  }

  int size() {
    return buckets.size();
  }

  private Bucket full(Instant now) {
    return new Bucket(capacity * WINDOW_MICROS, now, now);
  }

  /**
   * {@code bucket} refilled up to {@code now}. Whole microseconds are added, and {@code refilledAt}
   * moves by exactly those, so no fraction is lost; a clock that went back adds nothing.
   */
  private Bucket refill(Bucket bucket, Instant now) {
    if (!now.isAfter(bucket.refilledAt())) {
      return bucket;
    }
    long elapsedMicros = ChronoUnit.MICROS.between(bucket.refilledAt(), now);
    if (elapsedMicros >= WINDOW_MICROS) {
      return new Bucket(capacity * WINDOW_MICROS, now, bucket.touchedAt());
    }
    long level = Math.min(capacity * WINDOW_MICROS, bucket.level() + elapsedMicros * capacity);
    return new Bucket(
        level, bucket.refilledAt().plus(elapsedMicros, ChronoUnit.MICROS), bucket.touchedAt());
  }
}
