package com.company.fixtures.archunit.compliant.feature.thing.infrastructure.persistence;

import org.springframework.data.repository.Repository;

/** Spring Data interface inside infrastructure.persistence. */
interface ThingSpringDataRepository extends Repository<Object, Long> {

  long count();
}
