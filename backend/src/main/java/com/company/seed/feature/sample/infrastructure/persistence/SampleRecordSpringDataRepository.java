package com.company.seed.feature.sample.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * DEMO ONLY. The Spring Data access to {@code sample.sample_record}: only what {@link
 * JpaSampleRecordRepository} needs, and no {@code delete*} (soft delete is a save; restore is a
 * native update).
 */
interface SampleRecordSpringDataRepository extends Repository<SampleRecordJpaEntity, UUID> {

  SampleRecordJpaEntity saveAndFlush(SampleRecordJpaEntity entity);

  Optional<SampleRecordJpaEntity> findById(UUID id);

  Page<SampleRecordJpaEntity> findAll(Pageable pageable);

  List<SampleRecordJpaEntity> findAllByOrderByCreatedAtAscIdAsc();
}
