package com.company.fixtures.archunit.domainspring.domain;

import org.springframework.util.Assert;

/** Violation: a domain class importing org.springframework. */
public class SpringAwareDomainObject {

  public SpringAwareDomainObject(String name) {
    Assert.hasText(name, "name");
  }
}
