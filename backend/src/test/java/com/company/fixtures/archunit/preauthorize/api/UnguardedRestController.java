package com.company.fixtures.archunit.preauthorize.api;

import com.company.fixtures.archunit.compliant.feature.thing.api.SignedIn;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Violation: REST handlers without {@code @PreAuthorize}, next to guarded ones and to public
 * methods that are not handlers (which the rule leaves alone).
 */
@RestController
public class UnguardedRestController {

  @GetMapping("/list")
  public String list() {
    return "unguarded";
  }

  @GetMapping("/hidden")
  String packagePrivateHandler() {
    return "unguarded";
  }

  @GetMapping("/guarded")
  @PreAuthorize("isAuthenticated()")
  public String guarded() {
    return "guarded";
  }

  @GetMapping("/guarded-by-meta-annotation")
  @SignedIn
  public String guardedByMetaAnnotation() {
    return "guarded";
  }

  @InitBinder
  public void binder(WebDataBinder binder) {
    binder.setDisallowedFields("id");
  }

  @ExceptionHandler(IllegalStateException.class)
  public String failed(IllegalStateException ex) {
    return "failed";
  }

  public String helper() {
    return "not a handler";
  }
}
