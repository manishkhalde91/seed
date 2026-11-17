package com.company.fixtures.archunit.compliant.feature.thing.application;

import com.company.fixtures.archunit.compliant.common.api.PageResult;
import com.company.fixtures.archunit.compliant.feature.thing.domain.Thing;
import com.company.fixtures.archunit.compliant.feature.thing.domain.ThingRepository;

/** Application service using the repository interface and the shared common.api type. */
public class ThingService {

  private final ThingRepository repository;

  public ThingService(ThingRepository repository) {
    this.repository = repository;
  }

  public PageResult<Thing> list(int page, int size) {
    return repository.findPage(page, size);
  }
}
