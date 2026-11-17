package com.company.seed.common.idempotency.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Reads only. Claims, take-overs, completion, release and retention are statements in {@link
 * JpaIdempotencyRecordRepository}, so this interface declares no {@code delete*} or {@code remove*}
 * (SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE).
 */
interface IdempotencyRecordSpringDataRepository
    extends Repository<IdempotencyRecordJpaEntity, IdempotencyRecordJpaEntity.Key> {

  Optional<IdempotencyRecordJpaEntity> findByCallerAndIdempotencyKeyAndCreatedAtGreaterThanEqual(
      String caller, String idempotencyKey, Instant createdAtOrAfter);
}
