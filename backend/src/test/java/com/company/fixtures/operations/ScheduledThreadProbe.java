package com.company.fixtures.operations;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;

/** Test-only: a {@code @Scheduled} task that records the thread it last ran on. */
public class ScheduledThreadProbe {

  private final AtomicReference<String> lastThread = new AtomicReference<>();

  @Scheduled(fixedDelay = 100)
  void run() {
    lastThread.set(Thread.currentThread().getName());
  }

  /** The name of the thread of the latest run; {@code null} before the first. */
  public String lastThread() {
    return lastThread.get();
  }
}
