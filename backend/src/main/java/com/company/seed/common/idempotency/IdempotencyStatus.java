package com.company.seed.common.idempotency;

/** The state of an idempotency record; stored by name in {@code status}. */
public enum IdempotencyStatus {
  /** A request holds the key and its handler has not finished. */
  IN_PROGRESS,
  /** The response is stored and is replayed to every repeat of the request. */
  COMPLETED
}
