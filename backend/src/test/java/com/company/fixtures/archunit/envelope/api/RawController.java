package com.company.fixtures.archunit.envelope.api;

import com.company.fixtures.archunit.envelope.lookalike.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violation: REST endpoints that bypass the common.api ApiResponse envelope. */
@RestController
public class RawController {

  public String plain() {
    return "not enveloped";
  }

  public ResponseEntity<String> entityWithoutEnvelope() {
    return ResponseEntity.ok("not enveloped");
  }

  public ResponseEntity<ApiResponse<String>> lookalikeEnvelope() {
    return ResponseEntity.ok(new ApiResponse<>(true, "not the common.api envelope"));
  }

  @GetMapping("/hidden")
  String packagePrivateHandler() {
    return "not enveloped";
  }
}
