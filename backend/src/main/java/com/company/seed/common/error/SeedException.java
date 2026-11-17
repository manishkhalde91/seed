package com.company.seed.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of every exception the application throws on purpose (errors-and-logging.md §1). Framework
 * free, so the domain layer can throw it. Features extend one of the five subclasses; modules never
 * add new base classes.
 *
 * <p>{@link #getSafeMessage()} is written to the response and must be safe to show to a client;
 * {@link #getMessage()} is the same text and is only ever logged.
 */
public abstract class SeedException extends RuntimeException {

  private final String errorCode;
  private final int httpStatus;
  private final String safeMessage;
  private final Map<String, Object> details;
  private final boolean retryable;

  protected SeedException(
      String errorCode,
      int httpStatus,
      String safeMessage,
      Map<String, Object> details,
      boolean retryable,
      Throwable cause) {
    super(safeMessage, cause);
    if (errorCode == null || errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode must not be blank");
    }
    if (safeMessage == null || safeMessage.isBlank()) {
      throw new IllegalArgumentException("safeMessage must not be blank");
    }
    if (httpStatus < 400 || httpStatus > 599) {
      throw new IllegalArgumentException("httpStatus must be 4xx or 5xx: " + httpStatus);
    }
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
    this.safeMessage = safeMessage;
    // Diagnostic maps may hold null values, which Map.copyOf would reject.
    this.details =
        details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    this.retryable = retryable;
  }

  /** Stable {@code UPPER_SNAKE} code written to {@code errorCode}. */
  public String getErrorCode() {
    return errorCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  /** Client-safe text written to {@code message}. */
  public String getSafeMessage() {
    return safeMessage;
  }

  /** Extra diagnostic values for logs; never written to the response. */
  public Map<String, Object> getDetails() {
    return details;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
