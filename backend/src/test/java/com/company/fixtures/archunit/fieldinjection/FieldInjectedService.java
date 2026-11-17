package com.company.fixtures.archunit.fieldinjection;

import org.springframework.beans.factory.annotation.Autowired;

/** Violation: field injection instead of constructor injection. */
public class FieldInjectedService {

  @Autowired private Runnable collaborator;

  public void run() {
    collaborator.run();
  }
}
