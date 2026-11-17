package com.company.fixtures.archunit.compliant.feature.thing.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Spring Data interface without a hard delete: deletion is a save of a soft-deleted aggregate. */
interface SoftDeletingThingSpringDataRepository extends Repository<Object, Long> {

  Object save(Object thing);

  Optional<Object> findById(Long id);

  @Modifying
  @Query(
      value = "UPDATE thing SET deleted_at = NULL WHERE id = ?1 AND deleted_at IS NOT NULL",
      nativeQuery = true)
  int restore(Long id);
}
