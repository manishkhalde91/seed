package com.company.seed.common.auditing;

import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;

/**
 * An auditable entity that is deleted by marking it (data-and-migrations.md §5). Each subclass
 * declares {@code @SQLRestriction("deleted_at IS NULL")} itself, so deleted rows are hidden from
 * JPA reads; there is no global filter. The application service deletes with {@code
 * markDeleted(clock.instant(), currentActor.id())} and saves; a restore is a native update in the
 * repository, because the restriction hides the row from JPQL.
 */
@MappedSuperclass
public abstract class SoftDeletableEntity extends AuditableEntity {

  private Instant deletedAt;
  private String deletedBy;

  public void markDeleted(Instant at, String by) {
    this.deletedAt = Objects.requireNonNull(at, "at");
    this.deletedBy = Objects.requireNonNull(by, "by");
  }

  /**
   * Clears the deletion on an instance that was already managed before it was deleted (in the same
   * persistence context). A normal restore cannot load the row, which {@code @SQLRestriction}
   * hides, and is the repository's native update instead.
   */
  public void restore() {
    this.deletedAt = null;
    this.deletedBy = null;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public String getDeletedBy() {
    return deletedBy;
  }
}
