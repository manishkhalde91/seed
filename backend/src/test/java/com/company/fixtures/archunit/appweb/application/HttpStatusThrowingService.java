package com.company.fixtures.archunit.appweb.application;

import org.springframework.web.server.ResponseStatusException;

/** Violation: an application class importing org.springframework.web. */
public class HttpStatusThrowingService {

  public void fail() {
    throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
  }
}
