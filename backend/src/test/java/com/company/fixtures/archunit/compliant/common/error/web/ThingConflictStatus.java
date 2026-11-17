package com.company.fixtures.archunit.compliant.common.error.web;

import com.company.fixtures.archunit.compliant.common.error.ThingConflictException;
import org.springframework.http.HttpStatus;

/** common.error.web is the web adapter of common.error, so it may use Spring. */
public final class ThingConflictStatus {

  private ThingConflictStatus() {}

  public static HttpStatus of(ThingConflictException exception) {
    return HttpStatus.CONFLICT;
  }
}
