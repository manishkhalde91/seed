package com.company.fixtures.archunit.springdatadelete.infrastructure.persistence;

import org.springframework.data.repository.Repository;

/** Violation: a Spring Data interface that declares a hard delete. */
public interface DeletingThingRepository extends Repository<Object, Long> {

  void deleteById(Long id);
}
