package com.company.seed.common.operations.infrastructure.ws;

/**
 * A client frame refused before it is dispatched: thrown by an inbound channel interceptor, it
 * becomes an {@code ERROR} frame whose {@code message} is {@link #code()} ({@link StompErrors}),
 * and the connection closes. An expected outcome, not a failure: it carries no stack trace.
 */
public class StompRejectedException extends RuntimeException {

  private final String code;

  public StompRejectedException(String code) {
    super("STOMP frame rejected: " + code, null, false, false);
    this.code = code;
  }

  /** One of the {@link StompErrors} codes. */
  public String code() {
    return code;
  }
}
