package com.company.fixtures.archunit.springdataquery.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Violation: a hard delete hidden behind a name that is neither delete* nor remove*. */
public interface PurgingThingRepository extends Repository<Object, Long> {

  @Modifying
  @Query(value = "  delete from thing where id = ?1", nativeQuery = true)
  int purge(Long id);
}
