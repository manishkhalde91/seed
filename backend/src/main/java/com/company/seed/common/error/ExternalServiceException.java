package com.company.seed.common.error;

import java.util.Map;

/**
 * 502/503/504: an external dependency failed, is unavailable or timed out. Infrastructure adapters
 * translate vendor exceptions into this one. Retryable unless the dependency answered with a 4xx.
 */
public class ExternalServiceException extends SeedException {

  private final String dependency;

  /**
   * @param dependency name of the dependency, for logs ({@code "object-storage"})
   * @param httpStatus 502 (failed), 503 (unavailable) or 504 (timed out)
   * @param errorCode e.g. {@link ErrorCode#EXTERNAL_SERVICE_ERROR}, {@link
   *     ErrorCode#SERVICE_UNAVAILABLE}, {@link ErrorCode#EXTERNAL_SERVICE_TIMEOUT}
   * @param retryable false when the dependency rejected the request with a 4xx
   */
  public ExternalServiceException(
      String dependency,
      int httpStatus,
      String errorCode,
      String safeMessage,
      boolean retryable,
      Throwable cause) {
    super(errorCode, checkStatus(httpStatus), safeMessage, Map.of(), retryable, cause);
    this.dependency = dependency;
  }

  public String getDependency() {
    return dependency;
  }

  private static int checkStatus(int httpStatus) {
    if (httpStatus != 502 && httpStatus != 503 && httpStatus != 504) {
      throw new IllegalArgumentException("httpStatus must be 502, 503 or 504: " + httpStatus);
    }
    return httpStatus;
  }
}
