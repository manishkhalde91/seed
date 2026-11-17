package com.company.seed.common.operations.api;

import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The data of a 202 that started an operation (api-standards.md §3): where to follow it. The
 * response's {@code Location} is {@link #location}.
 */
@Schema(requiredProperties = {"operationId", "status", "version", "statusTopic"})
public record OperationAcceptedResponse(
    UUID operationId, OperationStatus status, int version, String statusTopic) {

  /** The REST snapshot of operation {@code id}. */
  public static String location(UUID id) {
    return OperationController.BASE + "/" + id;
  }

  public static OperationAcceptedResponse of(Operation operation) {
    return new OperationAcceptedResponse(
        operation.id(),
        operation.status(),
        operation.version(),
        OperationResponse.statusTopic(operation.id()));
  }
}
