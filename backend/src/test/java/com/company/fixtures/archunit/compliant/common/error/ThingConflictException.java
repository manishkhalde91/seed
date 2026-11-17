package com.company.fixtures.archunit.compliant.common.error;

/** A framework-free common.error exception. */
public class ThingConflictException extends RuntimeException {

  public ThingConflictException(String message) {
    super(message);
  }
}
