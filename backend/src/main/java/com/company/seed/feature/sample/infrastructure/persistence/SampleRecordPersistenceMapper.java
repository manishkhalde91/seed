package com.company.seed.feature.sample.infrastructure.persistence;

import com.company.seed.feature.sample.domain.SampleRecord;

/** DEMO ONLY. Hand-written mapping between the sample aggregate and its JPA entity. */
final class SampleRecordPersistenceMapper {

  private SampleRecordPersistenceMapper() {}

  static SampleRecord toDomain(SampleRecordJpaEntity entity) {
    return SampleRecord.stored(
        entity.getId(),
        entity.getName(),
        entity.getDescription(),
        entity.getVersion(),
        entity.getCreatedAt(),
        entity.getCreatedBy(),
        entity.getUpdatedAt(),
        entity.getUpdatedBy(),
        entity.getDeletedAt(),
        entity.getDeletedBy());
  }

  static SampleRecordJpaEntity toNewEntity(SampleRecord sampleRecord) {
    return new SampleRecordJpaEntity(
        sampleRecord.id(), sampleRecord.name(), sampleRecord.description());
  }

  /** Copies what the aggregate may change onto the loaded entity. */
  static void apply(SampleRecord sampleRecord, SampleRecordJpaEntity entity) {
    entity.replace(sampleRecord.name(), sampleRecord.description());
    if (sampleRecord.isDeleted() && !entity.isDeleted()) {
      entity.markDeleted(sampleRecord.deletedAt(), sampleRecord.deletedBy());
    }
  }
}
