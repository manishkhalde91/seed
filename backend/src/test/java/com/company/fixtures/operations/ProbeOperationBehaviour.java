package com.company.fixtures.operations;

import com.company.seed.common.error.BusinessRuleException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only: what the probe operation's work does on each attempt. Reset before each test; counts
 * the attempts of the current test.
 */
public class ProbeOperationBehaviour {

  /** How an attempt ends. */
  public enum Mode {
    SUCCEED,
    FAIL_TWICE_THEN_SUCCEED,
    ALWAYS_FAIL,
    PERMANENT_FAILURE,
    THROW_FROM_LISTENER
  }

  private volatile Mode mode = Mode.SUCCEED;
  private final AtomicInteger attempts = new AtomicInteger();

  public void reset(Mode mode) {
    this.mode = mode;
    attempts.set(0);
  }

  public int attempts() {
    return attempts.get();
  }

  public Mode mode() {
    return mode;
  }

  Map<String, Object> attempt() {
    int attempt = attempts.incrementAndGet();
    return switch (mode) {
      case SUCCEED, THROW_FROM_LISTENER -> Map.of("attempt", attempt);
      case FAIL_TWICE_THEN_SUCCEED -> {
        if (attempt <= 2) {
          throw new IllegalStateException("probe failure " + attempt);
        }
        yield Map.of("attempt", attempt);
      }
      case ALWAYS_FAIL -> throw new IllegalStateException("probe failure " + attempt);
      case PERMANENT_FAILURE -> throw new BusinessRuleException("PROBE_RULE", "Probe rule broken");
    };
  }
}
