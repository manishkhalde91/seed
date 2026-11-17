package com.company.seed.common.idempotency.infrastructure.persistence;

import com.company.seed.common.idempotency.IdempotencyRecord;
import com.company.seed.common.idempotency.IdempotencyRecordRepository;
import com.company.seed.common.idempotency.IdempotencyStatus;
import com.company.seed.common.idempotency.StoredResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IdempotencyRecordRepository} on PostgreSQL. The claim is an {@code INSERT … ON CONFLICT DO
 * NOTHING} on the unique {@code (caller, idempotency_key)}, and every later write is conditional on
 * the claim token, so the outcome is decided by the database across threads and instances. Rows
 * past retention count as absent. Deletes (expired key, release and retention) are native
 * statements here, never Spring Data methods. Each method is {@code @Transactional}: the
 * idempotency filter calls it outside any transaction, so each statement commits on return (a claim
 * must be visible to concurrent duplicates before the handler runs). Times are stored to the
 * microsecond, as {@code timestamptz} keeps them.
 */
@Repository
class JpaIdempotencyRecordRepository implements IdempotencyRecordRepository {

  private static final String DELETE_EXPIRED_KEY =
      """
      DELETE FROM common.idempotency_record
       WHERE caller = :caller AND idempotency_key = :key AND created_at < :expiredBefore
      """;

  private static final String CLAIM =
      """
      INSERT INTO common.idempotency_record
             (caller, idempotency_key, request_hash, claim_token, status, created_at, updated_at)
      VALUES (:caller, :key, :requestHash, :claimToken, 'IN_PROGRESS', :now, :now)
      ON CONFLICT ON CONSTRAINT uq_idempotency_record__caller_idempotency_key DO NOTHING
      """;

  private static final String TAKE_OVER =
      """
      UPDATE common.idempotency_record
         SET claim_token = :newClaimToken, created_at = :now, updated_at = :now
       WHERE caller = :caller
         AND idempotency_key = :key
         AND status = 'IN_PROGRESS'
         AND request_hash = :requestHash
         AND claim_token = :seenClaimToken
         AND updated_at <= :claimedAtOrBefore
      """;

  /** JPQL, so the nullable parameters take their types from the mapped attributes. */
  private static final String COMPLETE =
      """
      UPDATE IdempotencyRecordJpaEntity r
         SET r.status = 'COMPLETED',
             r.responseStatus = :status,
             r.responseContentType = :contentType,
             r.responseLocation = :location,
             r.responseBody = :body,
             r.updatedAt = :now
       WHERE r.caller = :caller
         AND r.idempotencyKey = :key
         AND r.claimToken = :claimToken
         AND r.status = 'IN_PROGRESS'
      """;

  private static final String RELEASE =
      """
      DELETE FROM common.idempotency_record
       WHERE caller = :caller
         AND idempotency_key = :key
         AND claim_token = :claimToken
         AND status = 'IN_PROGRESS'
      """;

  private static final String DELETE_CREATED_BEFORE =
      "DELETE FROM common.idempotency_record WHERE created_at < :cutoff";

  private final EntityManager entityManager;
  private final IdempotencyRecordSpringDataRepository records;

  /**
   * Takes the factory, not an {@code EntityManager}: no shared {@code EntityManager} bean is
   * registered for constructor injection, so the transaction-bound proxy is created here.
   */
  JpaIdempotencyRecordRepository(
      EntityManagerFactory entityManagerFactory, IdempotencyRecordSpringDataRepository records) {
    this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    this.records = records;
  }

  @Override
  @Transactional
  public boolean claim(
      String caller,
      String key,
      String requestHash,
      UUID claimToken,
      Instant now,
      Instant expiredBefore) {
    // Both statements run in one transaction: a concurrent claim of the same key waits on the
    // deleted row's lock, then conflicts with the row inserted here.
    entityManager
        .createNativeQuery(DELETE_EXPIRED_KEY)
        .setParameter("caller", caller)
        .setParameter("key", key)
        .setParameter("expiredBefore", micros(expiredBefore))
        .executeUpdate();
    return entityManager
            .createNativeQuery(CLAIM)
            .setParameter("caller", caller)
            .setParameter("key", key)
            .setParameter("requestHash", requestHash)
            .setParameter("claimToken", claimToken)
            .setParameter("now", micros(now))
            .executeUpdate()
        == 1;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<IdempotencyRecord> findByKey(String caller, String key, Instant expiredBefore) {
    return records
        .findByCallerAndIdempotencyKeyAndCreatedAtGreaterThanEqual(
            caller, key, micros(expiredBefore))
        .map(JpaIdempotencyRecordRepository::toRecord);
  }

  @Override
  @Transactional
  public boolean takeOver(
      String caller,
      String key,
      String requestHash,
      UUID seenClaimToken,
      UUID newClaimToken,
      Instant claimedAtOrBefore,
      Instant now) {
    return entityManager
            .createNativeQuery(TAKE_OVER)
            .setParameter("caller", caller)
            .setParameter("key", key)
            .setParameter("requestHash", requestHash)
            .setParameter("seenClaimToken", seenClaimToken)
            .setParameter("newClaimToken", newClaimToken)
            .setParameter("claimedAtOrBefore", micros(claimedAtOrBefore))
            .setParameter("now", micros(now))
            .executeUpdate()
        == 1;
  }

  @Override
  @Transactional
  public boolean complete(
      String caller, String key, UUID claimToken, StoredResponse response, Instant now) {
    return entityManager
            .createQuery(COMPLETE)
            .setParameter("status", response.status())
            .setParameter("contentType", response.contentType())
            .setParameter("location", response.location())
            .setParameter("body", response.body())
            .setParameter("now", micros(now))
            .setParameter("caller", caller)
            .setParameter("key", key)
            .setParameter("claimToken", claimToken)
            .executeUpdate()
        == 1;
  }

  @Override
  @Transactional
  public boolean release(String caller, String key, UUID claimToken) {
    return entityManager
            .createNativeQuery(RELEASE)
            .setParameter("caller", caller)
            .setParameter("key", key)
            .setParameter("claimToken", claimToken)
            .executeUpdate()
        == 1;
  }

  @Override
  @Transactional
  public int deleteCreatedBefore(Instant cutoff) {
    return entityManager
        .createNativeQuery(DELETE_CREATED_BEFORE)
        .setParameter("cutoff", micros(cutoff))
        .executeUpdate();
  }

  private static IdempotencyRecord toRecord(IdempotencyRecordJpaEntity entity) {
    IdempotencyStatus status = IdempotencyStatus.valueOf(entity.getStatus());
    StoredResponse response =
        status == IdempotencyStatus.COMPLETED
            ? new StoredResponse(
                entity.getResponseStatus(),
                entity.getResponseContentType(),
                entity.getResponseLocation(),
                entity.getResponseBody() == null ? new byte[0] : entity.getResponseBody())
            : null;
    return new IdempotencyRecord(
        entity.getCaller(),
        entity.getIdempotencyKey(),
        entity.getRequestHash(),
        entity.getClaimToken(),
        status,
        response,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
