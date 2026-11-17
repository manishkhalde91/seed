package com.company.fixtures.idempotency;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only state behind the idempotency probe endpoints: how often a request got past the
 * idempotency filter (the handler was invoked), and a latch that holds the next {@code /slow}
 * request inside the handler until the test releases it.
 */
public class IdempotencyProbe {

  private static final Duration MAX_BLOCK = Duration.ofSeconds(30);

  private final AtomicInteger invocations = new AtomicInteger();
  private final AtomicBoolean blockNext = new AtomicBoolean();
  private volatile CountDownLatch entered = new CountDownLatch(1);
  private volatile CountDownLatch released = new CountDownLatch(1);

  public void reset() {
    invocations.set(0);
    blockNext.set(false);
    entered = new CountDownLatch(1);
    released = new CountDownLatch(1);
  }

  public int invocations() {
    return invocations.get();
  }

  int invoked() {
    return invocations.incrementAndGet();
  }

  /** The next {@code /slow} request waits in the handler until {@link #release()}. */
  public void blockNext() {
    blockNext.set(true);
  }

  /** Waits until the blocked request is inside the handler. */
  public void awaitEntered() throws InterruptedException {
    if (!entered.await(MAX_BLOCK.toSeconds(), TimeUnit.SECONDS)) {
      throw new AssertionError("The blocked request never reached the handler");
    }
  }

  public void release() {
    released.countDown();
  }

  /** Called by {@code /slow}: blocks if this is the armed request. */
  void maybeBlock() throws InterruptedException {
    if (blockNext.compareAndSet(true, false)) {
      entered.countDown();
      if (!released.await(MAX_BLOCK.toSeconds(), TimeUnit.SECONDS)) {
        throw new IllegalStateException("The test never released the blocked request");
      }
    }
  }
}
