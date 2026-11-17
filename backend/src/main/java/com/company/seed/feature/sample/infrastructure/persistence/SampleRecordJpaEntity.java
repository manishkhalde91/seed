package com.company.seed.feature.sample.infrastructure.persistence;

import com.company.seed.common.auditing.SoftDeletableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

/**
 * DEMO ONLY. The {@code sample.sample_record} row. Deleted rows are hidden from every JPA read; the
 * ID comes from {@code IdGenerator}, and the {@code null} version of a new instance tells Spring
 * Data to insert it.
 */
@Entity
@Table(schema = "sample", name = "sample_record")
@SQLRestriction("deleted_at IS NULL")
class SampleRecordJpaEntity extends SoftDeletableEntity {

  @Id private UUID id;

  private String name;

  private String description;

  protected SampleRecordJpaEntity() {}

  SampleRecordJpaEntity(UUID id, String name, String description) {
    this.id = id;
    this.name = name;
    this.description = description;
  }

  UUID getId() {
    return id;
  }

  String getName() {
    return name;
  }

  String getDescription() {
    return description;
  }

  void replace(String name, String description) {
    this.name = name;
    this.description = description;
  }
}
