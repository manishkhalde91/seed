package com.company.seed.common.error;

import java.util.Map;

/** 404: the resource is missing or soft-deleted. Code: {@code <ENTITY>_NOT_FOUND}. */
public class ResourceNotFoundException extends SeedException {

  public ResourceNotFoundException(String errorCode, String safeMessage) {
    this(errorCode, safeMessage, Map.of());
  }

  public ResourceNotFoundException(
      String errorCode, String safeMessage, Map<String, Object> details) {
    super(errorCode, 404, safeMessage, details, false, null);
  }
}
