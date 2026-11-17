package com.company.fixtures.archunit.domaininfra.domain;

import com.company.fixtures.archunit.domaininfra.infrastructure.SomeAdapter;

/** Violation: a domain class importing an infrastructure class. */
public class AdapterAwareDomainObject {

  private final SomeAdapter adapter = new SomeAdapter();

  public SomeAdapter adapter() {
    return adapter;
  }
}
