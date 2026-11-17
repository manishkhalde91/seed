package com.company.fixtures.archunit.compliant.feature.thing.infrastructure.persistence;

import com.company.fixtures.archunit.compliant.common.api.PageResult;
import com.company.fixtures.archunit.compliant.feature.thing.domain.Thing;
import com.company.fixtures.archunit.compliant.feature.thing.domain.ThingRepository;
import java.util.List;

/** Implements the domain repository on top of the Spring Data interface. */
class JpaThingRepository implements ThingRepository {

  private final ThingSpringDataRepository springData;

  JpaThingRepository(ThingSpringDataRepository springData) {
    this.springData = springData;
  }

  @Override
  public PageResult<Thing> findPage(int page, int size) {
    return new PageResult<>(List.of(), springData.count());
  }
}
