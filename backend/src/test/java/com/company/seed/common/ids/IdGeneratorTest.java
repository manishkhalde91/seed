package com.company.seed.common.ids;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

  private static final Instant NOW = Instant.parse("2026-11-15T08:00:00.123Z");

  private final IdGenerator generator = new IdGenerator(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void createsVersion7WithTheRfc9562Variant() {
    UUID id = generator.newId();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(id.toString().charAt(14)).isEqualTo('7');
  }

  @Test
  void carriesTheClockMillisecondsInTheFirst48Bits() {
    UUID id = generator.newId();

    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
  }

  @Test
  void idsInTheSameMillisecondAreDistinct() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      ids.add(generator.newId());
    }

    assertThat(ids).hasSize(1000);
  }

  @Test
  void laterMillisecondsSortAfterEarlierOnes() {
    UUID earlier = new IdGenerator(Clock.fixed(NOW, ZoneOffset.UTC)).newId();
    UUID later = new IdGenerator(Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC)).newId();

    assertThat(later.toString()).isGreaterThan(earlier.toString());
  }

  @Test
  void idsFromOneInstanceStrictlyIncreaseWithinOneMillisecond() {
    UUID previous = generator.newId();
    for (int i = 0; i < 10_000; i++) {
      UUID next = generator.newId();

      assertThat(next.toString()).isGreaterThan(previous.toString());
      assertThat(next.version()).isEqualTo(7);
      assertThat(next.variant()).isEqualTo(2);
      previous = next;
    }
  }

  @Test
  void counterOverflowBorrowsTheNextMillisecond() {
    long first = timestamp(generator.newId());
    long last = first;
    // One millisecond holds at most 4096 IDs, so 10 000 IDs need later ones.
    for (int i = 0; i < 10_000; i++) {
      last = timestamp(generator.newId());
    }

    assertThat(first).isEqualTo(NOW.toEpochMilli());
    assertThat(last).isGreaterThan(first);
  }

  @Test
  void aClockSteppingBackNeverMovesTheTimestampBackwards() {
    MutableClock clock = new MutableClock(NOW);
    IdGenerator steppingBack = new IdGenerator(clock);
    UUID before = steppingBack.newId();

    clock.advance(Duration.ofMillis(-5));
    UUID after = steppingBack.newId();

    assertThat(after.toString()).isGreaterThan(before.toString());
    assertThat(timestamp(after)).isEqualTo(NOW.toEpochMilli());
    assertThat(after.version()).isEqualTo(7);
    assertThat(after.variant()).isEqualTo(2);
  }

  @Test
  void eachNewMillisecondSeedsTheCounterBelowHalfItsRange() {
    MutableClock clock = new MutableClock(NOW);
    IdGenerator seeded = new IdGenerator(clock);
    for (int i = 0; i < 100; i++) {
      clock.advance(Duration.ofMillis(1));
      UUID id = seeded.newId();

      assertThat(id.getMostSignificantBits() & 0x0FFF).isLessThan(0x0800);
      assertThat(timestamp(id)).isEqualTo(clock.millis());
    }
  }

  private static long timestamp(UUID id) {
    return id.getMostSignificantBits() >>> 16;
  }
}
