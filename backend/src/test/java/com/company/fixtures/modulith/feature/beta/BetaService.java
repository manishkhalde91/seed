package com.company.fixtures.modulith.feature.beta;

import com.company.fixtures.modulith.feature.alpha.internal.AlphaInternals;

/** Violation: beta reaches into alpha's internal package instead of its root package. */
public class BetaService {

  private final AlphaInternals internals = new AlphaInternals();

  public String borrow() {
    return internals.secret();
  }
}
