package com.company.seed.common.operations.domain;

import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.ResourceNotFoundException;
import java.util.Map;
import java.util.UUID;

/** No operation has the ID: 404 {@code OPERATION_NOT_FOUND}. */
public class OperationNotFoundException extends ResourceNotFoundException {

  public OperationNotFoundException(UUID id) {
    super(ErrorCode.OPERATION_NOT_FOUND, "Operation not found", Map.of("operationId", id));
  }
}
