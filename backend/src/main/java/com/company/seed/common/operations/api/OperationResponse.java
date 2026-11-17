package com.company.seed.common.operations.api;

import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An operation as {@code GET /api/v1/operations/{id}} returns it (the REST snapshot of the status
 * push). {@code version} is the status version; a client keeps the highest one it has seen. {@code
 * statusTopic} is the STOMP topic its live status is pushed to.
 */
@Schema(
    requiredProperties = {
      "operationId",
      "kind",
      "subjectId",
      "status",
      "version",
      "result",
      "errorCode",
      "errorMessage",
      "createdAt",
      "updatedAt",
      "correlationId",
      "statusTopic"
    })
public record OperationResponse(
    UUID operationId,
    String kind,
    @Schema(
            types = {"string", "null"},
            format = "uuid")
        UUID subjectId,
    OperationStatus status,
    int version,
    @Schema(types = {"object", "null"}) Map<String, Object> result,
    @Schema(types = {"string", "null"}) String errorCode,
    @Schema(types = {"string", "null"}) String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    String correlationId,
    String statusTopic) {

  /** The STOMP topic the status of operation {@code id} is pushed to (api-standards.md §9). */
  public static String statusTopic(UUID id) {
    return "/topic/operations/" + id;
  }

  public static OperationResponse of(Operation operation) {
    return new OperationResponse(
        operation.id(),
        operation.kind(),
        operation.subjectId(),
        operation.status(),
        operation.version(),
        operation.result(),
        operation.errorCode(),
        operation.errorMessage(),
        operation.createdAt(),
        operation.updatedAt(),
        operation.correlationId(),
        statusTopic(operation.id()));
  }
}
