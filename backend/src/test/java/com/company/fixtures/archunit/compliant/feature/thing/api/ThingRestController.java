package com.company.fixtures.archunit.compliant.feature.thing.api;

import com.company.fixtures.archunit.compliant.common.api.ApiResponse;
import com.company.fixtures.archunit.compliant.common.api.PageResult;
import com.company.fixtures.archunit.compliant.feature.thing.application.ThingService;
import com.company.fixtures.archunit.compliant.feature.thing.domain.Thing;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints that return the envelope and carry {@code @PreAuthorize}. */
@RestController
public class ThingRestController {

  private final ThingService service;

  public ThingRestController(ThingService service) {
    this.service = service;
  }

  @GetMapping("/things")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<PageResult<Thing>>> list() {
    return ResponseEntity.ok(new ApiResponse<>(true, "Things retrieved", service.list(0, 20)));
  }

  @GetMapping("/things/first")
  @SignedIn
  public ResponseEntity<ApiResponse<PageResult<Thing>>> firstPage() {
    return ResponseEntity.ok(new ApiResponse<>(true, "Things retrieved", service.list(0, 20)));
  }
}
