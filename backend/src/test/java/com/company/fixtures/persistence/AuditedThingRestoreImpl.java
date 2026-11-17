package com.company.fixtures.persistence;

import com.company.seed.common.actor.CurrentActor;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * A native update, because {@code @SQLRestriction} hides deleted rows from JPQL. The {@code
 * deleted_at IS NOT NULL} guard keeps it from bumping an active row's version. Like
 * {@code @Modifying(flushAutomatically = true, clearAutomatically = true)}, it flushes pending
 * changes first and clears the persistence context afterwards, so no managed instance keeps the old
 * version. Times are stored to the microsecond, as {@code timestamptz} keeps them.
 */
class AuditedThingRestoreImpl implements AuditedThingRestore {

  private static final String RESTORE =
      """
      UPDATE fixture.audited_thing
         SET deleted_at = NULL, deleted_by = NULL,
             updated_at = :updatedAt, updated_by = :updatedBy, version = version + 1
       WHERE id = :id AND deleted_at IS NOT NULL
      """;

  private final EntityManager entityManager;
  private final Clock clock;
  private final CurrentActor currentActor;

  AuditedThingRestoreImpl(EntityManager entityManager, Clock clock, CurrentActor currentActor) {
    this.entityManager = entityManager;
    this.clock = clock;
    this.currentActor = currentActor;
  }

  @Override
  @Transactional
  public boolean restore(UUID id) {
    entityManager.flush();
    int restored =
        entityManager
            .createNativeQuery(RESTORE)
            .setParameter("updatedAt", clock.instant().truncatedTo(ChronoUnit.MICROS))
            .setParameter("updatedBy", currentActor.id())
            .setParameter("id", id)
            .executeUpdate();
    entityManager.clear();
    return restored == 1;
  }
}
