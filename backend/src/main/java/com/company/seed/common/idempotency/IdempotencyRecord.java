package com.company.seed.common.idempotency;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored idempotency record, owned by {@code caller} (the {@code CurrentActor} that claimed it):
 * the same key from two callers is two records. {@code updatedAt} is when the current claim was
 * taken (the claim itself, or the last take-over) until the record completes; {@code response} is
 * {@code null} while the record is {@link IdempotencyStatus#IN_PROGRESS IN_PROGRESS}.
 */
public record IdempotencyRecord(
    String caller,
    String key,
    String requestHash,
    UUID claimToken,
    IdempotencyStatus status,
    StoredResponse response,
    Instant createdAt,
    Instant updatedAt) {}
