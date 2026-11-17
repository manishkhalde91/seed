package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class IdempotencyPropertiesTest {

  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
  private static final DataSize ONE_MB = DataSize.ofMegabytes(1);

  @Test
  void defaultsAreADayFiveMinutesAndOneMegabyte() {
    IdempotencyProperties properties =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("seed.idempotency", IdempotencyProperties.class);

    assertThat(properties).isEqualTo(new IdempotencyProperties(DAY, FIVE_MINUTES, ONE_MB));
  }

  @Test
  void boundValuesAreUsed() {
    IdempotencyProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "seed.idempotency.retention", "2h",
                        "seed.idempotency.abandon-after", "30s",
                        "seed.idempotency.max-stored-response-size", "64KB")))
            .bindOrCreate("seed.idempotency", IdempotencyProperties.class);

    assertThat(properties)
        .isEqualTo(
            new IdempotencyProperties(
                Duration.ofHours(2), Duration.ofSeconds(30), DataSize.ofKilobytes(64)));
  }

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of("zero retention", Duration.ZERO, FIVE_MINUTES, ONE_MB),
        Arguments.of("negative retention", DAY.negated(), FIVE_MINUTES, ONE_MB),
        Arguments.of("zero abandon-after", DAY, Duration.ZERO, ONE_MB),
        Arguments.of("negative abandon-after", DAY, FIVE_MINUTES.negated(), ONE_MB),
        Arguments.of("retention equal to abandon-after", FIVE_MINUTES, FIVE_MINUTES, ONE_MB),
        Arguments.of("retention shorter than abandon-after", FIVE_MINUTES, DAY, ONE_MB),
        Arguments.of("null retention", null, FIVE_MINUTES, ONE_MB),
        Arguments.of("null abandon-after", DAY, null, ONE_MB),
        Arguments.of("null max-stored-response-size", DAY, FIVE_MINUTES, null),
        Arguments.of("zero max-stored-response-size", DAY, FIVE_MINUTES, DataSize.ofBytes(0)),
        Arguments.of("negative max-stored-response-size", DAY, FIVE_MINUTES, DataSize.ofBytes(-1)),
        Arguments.of("2 GB max-stored-response-size", DAY, FIVE_MINUTES, DataSize.ofGigabytes(2)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalid")
  void invalidSettingsAreRejected(
      String name, Duration retention, Duration abandonAfter, DataSize maxStoredResponseSize) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new IdempotencyProperties(retention, abandonAfter, maxStoredResponseSize));
  }
}
