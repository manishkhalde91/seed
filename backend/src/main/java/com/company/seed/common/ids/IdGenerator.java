package com.company.seed.common.ids;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Creates UUIDv7 identifiers (RFC 9562 §5.7): a 48-bit Unix millisecond timestamp from the
 * application {@link Clock}, the version nibble {@code 7}, a 12-bit counter in {@code rand_a}, the
 * variant bits {@code 10} and 62 random bits from {@link SecureRandom}.
 *
 * <p>The IDs of one instance strictly increase (RFC 9562 §6.2, method 1, "fixed bit-length
 * dedicated counter"): each new millisecond seeds the counter with a random 11-bit value (its top
 * bit stays 0, leaving at least 2048 increments of headroom); further IDs in the same millisecond
 * increment it. When the counter overflows, the generator borrows the next millisecond. Its
 * timestamp never moves backwards: if the clock steps back, IDs keep counting on the last timestamp
 * used until the clock catches up.
 */
@Component
public class IdGenerator {

  private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
  private static final long VERSION_7 = 0x7000L;
  private static final int COUNTER_MAX = 0x0FFF;
  private static final int COUNTER_SEED_BOUND = 0x0800;
  private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  private long lastMillis = Long.MIN_VALUE;
  private int counter;

  public IdGenerator(Clock clock) {
    this.clock = clock;
  }

  /** A new UUIDv7, greater than every ID this instance has created before. */
  public UUID newId() {
    long millis;
    int sequence;
    synchronized (this) {
      long now = clock.millis();
      if (now > lastMillis) {
        lastMillis = now;
        counter = random.nextInt(COUNTER_SEED_BOUND);
      } else if (counter < COUNTER_MAX) {
        counter++;
      } else {
        lastMillis++;
        counter = random.nextInt(COUNTER_SEED_BOUND);
      }
      millis = lastMillis;
      sequence = counter;
    }
    long mostSignificant = ((millis & TIMESTAMP_MASK) << 16) | VERSION_7 | sequence;
    long leastSignificant = (random.nextLong() & VARIANT_MASK) | VARIANT_RFC_9562;
    return new UUID(mostSignificant, leastSignificant);
  }
}
