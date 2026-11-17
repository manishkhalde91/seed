package com.company.fixtures.archunit.springdatacrud.infrastructure.persistence;

import org.springframework.data.repository.CrudRepository;

/** Violation: a Spring Data interface that inherits CrudRepository's delete methods. */
public interface CrudThingRepository extends CrudRepository<Object, Long> {}
