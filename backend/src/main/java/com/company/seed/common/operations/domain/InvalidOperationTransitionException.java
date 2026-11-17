package com.company.seed.common.operations.domain;

import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * A status change that lost its conditional update: a stale version, or a status the transition
 * does not start from. 409 {@code INVALID_STATE_TRANSITION}; never retried.
 */
public class InvalidOperationTransitionException extends ConflictException {

  public InvalidOperationTransitionException(UUID id, OperationStatus to, int expectedVersion) {
    super(
        ErrorCode.INVALID_STATE_TRANSITION,
        "The operation cannot change to that status",
        Map.of("operationId", id, "to", to.name(), "expectedVersion", expectedVersion));
  }
}
