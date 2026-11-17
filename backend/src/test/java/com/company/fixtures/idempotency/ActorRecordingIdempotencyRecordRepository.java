package com.company.fixtures.idempotency;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.idempotency.IdempotencyRecord;
import com.company.seed.common.idempotency.IdempotencyRecordRepository;
import com.company.seed.common.idempotency.StoredResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only decorator of the real repository: delegates every call and records the current actor of
 * each {@link #deleteCreatedBefore(Instant)}, so a test can see which scope the cleanup ran in.
 */
public class ActorRecordingIdempotencyRecordRepository implements IdempotencyRecordRepository {

  private final IdempotencyRecordRepository delegate;
  private final CurrentActor currentActor;
  private final List<String> retentionActors = new CopyOnWriteArrayList<>();

  public ActorRecordingIdempotencyRecordRepository(
      IdempotencyRecordRepository delegate, CurrentActor currentActor) {
    this.delegate = delegate;
    this.currentActor = currentActor;
  }

  /** The actors of the retention deletes so far, in order. */
  public List<String> retentionActors() {
    return List.copyOf(retentionActors);
  }

  @Override
  public boolean claim(
      String caller,
      String key,
      String requestHash,
      UUID claimToken,
      Instant now,
      Instant expiredBefore) {
    return delegate.claim(caller, key, requestHash, claimToken, now, expiredBefore);
  }

  @Override
  public Optional<IdempotencyRecord> findByKey(String caller, String key, Instant expiredBefore) {
    return delegate.findByKey(caller, key, expiredBefore);
  }

  @Override
  public boolean takeOver(
      String caller,
      String key,
      String requestHash,
      UUID seenClaimToken,
      UUID newClaimToken,
      Instant claimedAtOrBefore,
      Instant now) {
    return delegate.takeOver(
        caller, key, requestHash, seenClaimToken, newClaimToken, claimedAtOrBefore, now);
  }

  @Override
  public boolean complete(
      String caller, String key, UUID claimToken, StoredResponse response, Instant now) {
    return delegate.complete(caller, key, claimToken, response, now);
  }

  @Override
  public boolean release(String caller, String key, UUID claimToken) {
    return delegate.release(caller, key, claimToken);
  }

  @Override
  public int deleteCreatedBefore(Instant cutoff) {
    retentionActors.add(currentActor.id());
    return delegate.deleteCreatedBefore(cutoff);
  }
}
