package com.company.fixtures.archunit.appapi.application;

import com.company.fixtures.archunit.appapi.api.SomeResponse;

/** Violation: an application class importing an api (web) class. */
public class ResponseBuildingService {

  public SomeResponse load() {
    return new SomeResponse("x");
  }
}
