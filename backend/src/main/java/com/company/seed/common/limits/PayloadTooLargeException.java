package com.company.seed.common.limits;

import java.io.IOException;

/**
 * A request body grew past {@code seed.limits.max-body-size} while it was read (a body sent without
 * {@code Content-Length}). Thrown by the stream {@link RequestSizeFilter} wraps around the body, so
 * it is an {@link IOException}; readers usually wrap it (a JSON parse error, a {@code
 * ServletException}), and {@code GlobalExceptionHandler} and {@code JsonErrorController} answer it
 * 413 {@code PAYLOAD_TOO_LARGE} wherever it sits in the cause chain.
 */
public class PayloadTooLargeException extends IOException {

  private final String safeMessage;

  /**
   * @param limit the limit as configured ({@code 1MB}), named in the client-safe message
   */
  public PayloadTooLargeException(String limit) {
    super(RequestSizeFilter.message(limit));
    this.safeMessage = RequestSizeFilter.message(limit);
  }

  /** Client-safe text naming the limit: {@code Request body exceeds 1MB}. */
  public String getSafeMessage() {
    return safeMessage;
  }
}
