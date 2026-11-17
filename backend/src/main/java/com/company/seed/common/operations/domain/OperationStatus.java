package com.company.seed.common.operations.domain;

import java.util.Set;

/**
 * The status of a long-running operation and the transitions into it (architecture-diagrams.md §4):
 * {@code QUEUED → RUNNING → SUCCEEDED}, and {@code FAILED} from {@code QUEUED} or {@code RUNNING}.
 * {@code QUEUED} is only ever the starting status.
 */
public enum OperationStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED;

  /** The statuses a transition into this one may start from; empty for {@link #QUEUED}. */
  public Set<OperationStatus> allowedFrom() {
    return switch (this) {
      case QUEUED -> Set.of();
      case RUNNING -> Set.of(QUEUED);
      case SUCCEEDED -> Set.of(RUNNING);
      case FAILED -> Set.of(QUEUED, RUNNING);
    };
  }

  /** {@code SUCCEEDED} or {@code FAILED}: no transition leaves it. */
  public boolean isFinished() {
    return this == SUCCEEDED || this == FAILED;
  }
}
