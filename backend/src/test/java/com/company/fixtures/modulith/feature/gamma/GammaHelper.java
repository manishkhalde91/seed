package com.company.fixtures.modulith.feature.gamma;

/** In a package without {@code @ApplicationModule}: must not be detected as a module. */
public class GammaHelper {

  public String help() {
    return "gamma";
  }
}
