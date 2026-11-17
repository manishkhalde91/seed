package com.company.fixtures.modulith.feature.alpha;

import com.company.fixtures.modulith.feature.alpha.internal.AlphaInternals;

/** Public API of the alpha fixture module. */
public class AlphaFacade {

  private final AlphaInternals internals = new AlphaInternals();

  public String describe() {
    return internals.secret();
  }
}
