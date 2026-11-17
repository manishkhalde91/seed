package com.company.fixtures.persistence;

import java.util.UUID;

/** The native restore of a soft-deleted {@link AuditedThing} (data-and-migrations.md §5). */
public interface AuditedThingRestore {

  /**
   * Restores the row if it is currently deleted: clears {@code deleted_*}, sets {@code updated_*}
   * from the clock and the current actor and bumps {@code version}. A missing or active row is left
   * untouched.
   *
   * @return whether a row was restored
   */
  boolean restore(UUID id);
}
