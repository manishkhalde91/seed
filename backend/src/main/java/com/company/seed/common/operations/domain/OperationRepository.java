package com.company.seed.common.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The stored operations (backend-architecture.md §5). Status changes are conditional updates. */
public interface OperationRepository {

  /** Stores a new operation; its requester fills {@code created_by} and {@code updated_by}. */
  void insert(Operation operation);

  Optional<Operation> findById(UUID id);

  /**
   * Moves the operation to {@code to}, but only while its version is {@code expectedVersion} and
   * its status is one of {@code to.allowedFrom()}: bumps the version and stamps {@code updated_*}.
   *
   * @return the updated operation, or empty when no row matched (a stale version or a status the
   *     transition does not start from)
   */
  Optional<Operation> transition(
      UUID id,
      int expectedVersion,
      OperationStatus to,
      Map<String, Object> result,
      String errorCode,
      String errorMessage,
      Instant now,
      String actor);

  /** Deletes {@code SUCCEEDED} and {@code FAILED} operations created before {@code cutoff}. */
  int deleteFinishedCreatedBefore(Instant cutoff);
}
