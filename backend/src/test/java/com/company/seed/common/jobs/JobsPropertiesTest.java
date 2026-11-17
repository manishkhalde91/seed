package com.company.seed.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JobsPropertiesTest {

  private static final Duration WEEK = Duration.ofDays(7);
  private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

  @Test
  void positiveDurationsAreAccepted() {
    assertThat(new JobsProperties(WEEK, WEEK, FIVE_MINUTES).operationRetention()).isEqualTo(WEEK);
  }

  @Test
  void zeroNegativeOrMissingDurationsAreRejected() {
    assertThatThrownBy(() -> new JobsProperties(Duration.ZERO, WEEK, FIVE_MINUTES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JobsProperties(WEEK, Duration.ofDays(-1), FIVE_MINUTES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JobsProperties(WEEK, WEEK, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
