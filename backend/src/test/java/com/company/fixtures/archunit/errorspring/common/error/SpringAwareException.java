package com.company.fixtures.archunit.errorspring.common.error;

import org.springframework.http.HttpStatus;

/** Violation: a common.error exception that depends on Spring. */
public class SpringAwareException extends RuntimeException {

  private final HttpStatus status = HttpStatus.CONFLICT;

  public HttpStatus status() {
    return status;
  }
}
