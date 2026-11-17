package com.company.fixtures.archunit.domainapi.domain;

import com.company.fixtures.archunit.domainapi.api.SomeRequest;

/** Violation: a domain class importing an api (web) class. */
public class RequestAwareDomainObject {

  public static RequestAwareDomainObject from(SomeRequest request) {
    return new RequestAwareDomainObject();
  }
}
