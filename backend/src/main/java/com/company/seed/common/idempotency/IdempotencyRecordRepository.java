package com.company.seed.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The stored {@code Idempotency-Key} records (backend-architecture.md §5): plain Java, no JPA or
 * Spring Data types. A record belongs to a caller (the {@code CurrentActor} ID): every method takes
 * the caller and the key together, so one caller never sees, replays or changes another's record.
 * Every write that follows a claim also matches the claim token, so a request whose claim was taken
 * over changes nothing. Each method commits on its own unless the caller already runs a
 * transaction.
 */
public interface IdempotencyRecordRepository {

  /**
   * Atomically inserts an {@link IdempotencyStatus#IN_PROGRESS IN_PROGRESS} row for {@code caller}
   * and {@code key}, unless a live row for both exists. A row created before {@code expiredBefore}
   * (past retention) counts as absent: it is deleted and replaced in the same transaction.
   *
   * @return whether this call created the row (and so holds the claim)
   */
  boolean claim(
      String caller,
      String key,
      String requestHash,
      UUID claimToken,
      Instant now,
      Instant expiredBefore);

  /**
   * The row for {@code caller} and {@code key}, unless it was created before {@code expiredBefore}.
   */
  Optional<IdempotencyRecord> findByKey(String caller, String key, Instant expiredBefore);

  /**
   * Takes over an abandoned claim: installs {@code newClaimToken} only if the row is still {@code
   * IN_PROGRESS} with {@code requestHash}, still holds {@code seenClaimToken} and was last claimed
   * at or before {@code claimedAtOrBefore}. Of two concurrent take-overs at most one succeeds. The
   * row's {@code created_at} restarts at {@code now}, so retention counts from the take-over.
   *
   * @return whether the claim was taken over
   */
  boolean takeOver(
      String caller,
      String key,
      String requestHash,
      UUID seenClaimToken,
      UUID newClaimToken,
      Instant claimedAtOrBefore,
      Instant now);

  /**
   * Stores the response and marks the row {@link IdempotencyStatus#COMPLETED COMPLETED}, if this
   * claim still holds it.
   *
   * @return whether the row was completed
   */
  boolean complete(
      String caller, String key, UUID claimToken, StoredResponse response, Instant now);

  /**
   * Deletes the row, if this claim still holds it, so a retry runs again.
   *
   * @return whether the row was deleted
   */
  boolean release(String caller, String key, UUID claimToken);

  /**
   * Deletes every row created before {@code cutoff} (retention).
   *
   * @return the number of rows deleted
   */
  int deleteCreatedBefore(Instant cutoff);
}
