package com.company.seed.common.api;

import java.time.Instant;
import java.util.List;

/**
 * Error envelope for every failed REST request (api-standards.md §3). {@code errors} is always
 * present and empty when there are no field errors; {@code message} is always a fixed, safe text.
 */
public record ApiErrorResponse(
    boolean success,
    int status,
    String message,
    String errorCode,
    List<FieldError> errors,
    Instant timestamp,
    String path,
    String correlationId) {

  public ApiErrorResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }
}
