package com.company.seed.common.error;

/**
 * The generic error codes of api-standards.md §2. Codes are stable: a message may change, a code
 * may not. Entity- and module-specific codes ({@code SAMPLE_RECORD_NOT_FOUND}) live with the
 * feature that throws them.
 */
public final class ErrorCode {

  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String FORBIDDEN = "FORBIDDEN";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
  public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
  public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";
  public static final String OPERATION_NOT_FOUND = "OPERATION_NOT_FOUND";
  public static final String OPERATION_FAILED = "OPERATION_FAILED";
  public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
  public static final String IDEMPOTENCY_REQUEST_IN_PROGRESS = "IDEMPOTENCY_REQUEST_IN_PROGRESS";
  public static final String DATA_INTEGRITY_VIOLATION = "DATA_INTEGRITY_VIOLATION";
  public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
  public static final String RATE_LIMITED = "RATE_LIMITED";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  public static final String EXTERNAL_SERVICE_ERROR = "EXTERNAL_SERVICE_ERROR";
  public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
  public static final String EXTERNAL_SERVICE_TIMEOUT = "EXTERNAL_SERVICE_TIMEOUT";

  private ErrorCode() {}
}
