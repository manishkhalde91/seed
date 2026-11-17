package com.company.fixtures.modulith.feature.delta;

import com.company.fixtures.modulith.feature.alpha.AlphaFacade;

/** Violation: uses alpha's public API although delta allows no dependencies. */
public class DeltaService {

  private final AlphaFacade alpha = new AlphaFacade();

  public String describe() {
    return alpha.describe();
  }
}
