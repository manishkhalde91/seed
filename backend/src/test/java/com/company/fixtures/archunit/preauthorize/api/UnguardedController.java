package com.company.fixtures.archunit.preauthorize.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Violation: a plain {@code @Controller} handler without {@code @PreAuthorize}. */
@Controller
public class UnguardedController {

  @GetMapping("/page")
  public String page() {
    return "unguarded";
  }
}
