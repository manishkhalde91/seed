package com.company.fixtures.archunit.apirepo.api;

import com.company.fixtures.archunit.apirepo.domain.ThingRepository;
import java.util.List;

/** Violation: an api class using a repository directly. */
public class ThingController {

  private final ThingRepository repository;

  public ThingController(ThingRepository repository) {
    this.repository = repository;
  }

  public List<String> list() {
    return repository.findAll();
  }
}
