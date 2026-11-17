package com.company.seed.common.operations.application;

import com.company.seed.common.operations.domain.Operation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The status push of an operation (api-standards.md §9, not enveloped), in the documented member
 * order. The one builder of it: the {@code pg_notify} payload that every instance forwards to
 * {@code /topic/operations/{id}} and the STOMP snapshot on {@code /app/operations/{id}} are both
 * made here, so they never drift apart. {@code errorCode} and {@code errorMessage} are set only for
 * a {@code FAILED} operation; {@code correlationId} is the one of the request that started it.
 */
public final class OperationStatusPayload {

  private OperationStatusPayload() {}

  /** The push payload of {@code operation}; a mutable map, so {@code null} members stay. */
  public static Map<String, Object> of(Operation operation) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operationId", operation.id());
    payload.put("kind", operation.kind());
    payload.put("subjectId", operation.subjectId());
    payload.put("status", operation.status().name());
    payload.put("version", operation.version());
    payload.put("result", operation.result());
    payload.put("errorCode", operation.errorCode());
    payload.put("errorMessage", operation.errorMessage());
    payload.put("updatedAt", operation.updatedAt());
    payload.put("correlationId", operation.correlationId());
    return payload;
  }
}
