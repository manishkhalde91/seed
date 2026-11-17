package com.company.fixtures.archunit.springdata.application;

import org.springframework.data.repository.Repository;

/** Violation: a Spring Data interface outside infrastructure.persistence. */
public interface MisplacedSpringDataRepository extends Repository<Object, Long> {}
