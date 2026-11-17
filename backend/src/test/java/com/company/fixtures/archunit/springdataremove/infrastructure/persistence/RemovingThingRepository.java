package com.company.fixtures.archunit.springdataremove.infrastructure.persistence;

import org.springframework.data.repository.Repository;

/** Violation: a Spring Data derived delete named remove*. */
public interface RemovingThingRepository extends Repository<Object, Long> {

  long removeByName(String name);
}
