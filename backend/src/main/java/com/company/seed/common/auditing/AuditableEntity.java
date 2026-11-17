package com.company.seed.common.auditing;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Audit columns of every exposed, mutable table (data-and-migrations.md §4). The times come from
 * the application {@code Clock} and the actor from {@code CurrentActor} ({@link
 * JpaAuditingConfig}); on insert {@code updated_*} equal {@code created_*}. {@code version} is the
 * optimistic lock: {@code null} until the first insert, which is how Spring Data tells a new entity
 * with an application-assigned ID from a stored one (Hibernate then starts it at 0). The ID lives
 * on the concrete entity. Audit fields are never part of a request DTO.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

  @CreatedDate
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @CreatedBy
  @Column(nullable = false, updatable = false)
  private String createdBy;

  @LastModifiedDate
  @Column(nullable = false)
  private Instant updatedAt;

  @LastModifiedBy
  @Column(nullable = false)
  private String updatedBy;

  @Version private Long version;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  /** The optimistic-lock version; {@code null} before the first insert. */
  public Long getVersion() {
    return version;
  }
}
