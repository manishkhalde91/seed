package com.company.seed.common.error;

import java.util.Map;

/**
 * 409: unique conflict, state conflict or idempotency key reuse. Codes: {@code
 * <ENTITY>_ALREADY_EXISTS}, {@link ErrorCode#INVALID_STATE_TRANSITION}, {@link
 * ErrorCode#IDEMPOTENCY_KEY_REUSED}.
 */
public class ConflictException extends SeedException {

  public ConflictException(String errorCode, String safeMessage) {
    this(errorCode, safeMessage, Map.of());
  }

  public ConflictException(String errorCode, String safeMessage, Map<String, Object> details) {
    super(errorCode, 409, safeMessage, details, false, null);
  }
}
