package com.company.fixtures.archunit.compliant.feature.thing.api;

import com.company.fixtures.archunit.compliant.common.api.PageResult;
import com.company.fixtures.archunit.compliant.feature.thing.application.ThingService;
import com.company.fixtures.archunit.compliant.feature.thing.domain.Thing;
import org.springframework.web.bind.annotation.RequestBody;

/** Web layer calling the application service, never a repository. */
public class ThingController {

  private final ThingService service;

  public ThingController(ThingService service) {
    this.service = service;
  }

  public PageResult<Thing> list() {
    return service.list(0, 20);
  }

  /** A request body without audit fields: the server fills them. */
  public void create(@RequestBody CreateThingRequest request) {}
}
