package com.company.seed.common.operations.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One long-running operation (realtime-and-storage.md §1.1). {@code version} is the {@code
 * status_version}: 1 when started, +1 on every status change, so a client keeps the highest version
 * it has seen. {@code result} is a small JSON object (for example {@code {"rowCount": 3}}); {@code
 * errorCode} and {@code errorMessage} are safe values, set only when {@code FAILED}.
 */
public record Operation(
    UUID id,
    String kind,
    UUID subjectId,
    String requestedBy,
    OperationStatus status,
    int version,
    Map<String, Object> result,
    String errorCode,
    String errorMessage,
    String correlationId,
    Instant createdAt,
    Instant updatedAt) {

  public Operation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(correlationId, "correlationId");
    // A JSON object may hold null members, which Map.copyOf rejects.
    result = result == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(result));
  }
}
