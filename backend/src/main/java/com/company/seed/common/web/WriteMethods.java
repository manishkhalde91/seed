package com.company.seed.common.web;

import java.util.Set;

/**
 * The HTTP methods that write: the ones the write rate limit counts and an {@code Idempotency-Key}
 * applies to.
 */
public final class WriteMethods {

  /** {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE}. */
  public static final Set<String> ALL = Set.of("POST", "PUT", "PATCH", "DELETE");

  private WriteMethods() {}
}
