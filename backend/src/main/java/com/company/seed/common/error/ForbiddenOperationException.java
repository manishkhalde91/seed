package com.company.seed.common.error;

import java.util.Map;

/** 403 {@link ErrorCode#FORBIDDEN}: an ownership check in an application service failed. */
public class ForbiddenOperationException extends SeedException {

  public ForbiddenOperationException(String safeMessage) {
    this(safeMessage, Map.of());
  }

  public ForbiddenOperationException(String safeMessage, Map<String, Object> details) {
    super(ErrorCode.FORBIDDEN, 403, safeMessage, details, false, null);
  }
}
