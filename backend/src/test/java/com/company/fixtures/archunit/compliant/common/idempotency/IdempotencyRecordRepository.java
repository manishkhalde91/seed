package com.company.fixtures.archunit.compliant.common.idempotency;

/** A repository interface outside feature/, as in {@code common.idempotency}. */
public interface IdempotencyRecordRepository {

  boolean exists(String key);
}
