package com.company.fixtures.archunit.appinfra.application;

import com.company.fixtures.archunit.appinfra.infrastructure.SomeAdapter;

/** Violation: an application class importing an infrastructure class. */
public class AdapterAwareService {

  private final SomeAdapter adapter;

  public AdapterAwareService(SomeAdapter adapter) {
    this.adapter = adapter;
  }

  public SomeAdapter adapter() {
    return adapter;
  }
}
