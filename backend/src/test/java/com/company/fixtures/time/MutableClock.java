package com.company.fixtures.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC test clock that stands still until a test sets or moves it. */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant now) {
    this.now = now;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public void advance(Duration duration) {
    this.now = now.plus(duration);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("The test clock is always UTC");
  }
}
