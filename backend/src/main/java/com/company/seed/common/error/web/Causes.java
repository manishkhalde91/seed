package com.company.seed.common.error.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/** Looks through an exception's cause chain. */
public final class Causes {

  private Causes() {}

  /**
   * The exception itself or the first of its causes that is a {@code type}; empty if none is. Safe
   * against a chain that loops back on itself.
   */
  public static <T extends Throwable> Optional<T> findCause(Throwable exception, Class<T> type) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = exception; t != null && seen.add(t); t = t.getCause()) {
      if (type.isInstance(t)) {
        return Optional.of(type.cast(t));
      }
    }
    return Optional.empty();
  }
}
