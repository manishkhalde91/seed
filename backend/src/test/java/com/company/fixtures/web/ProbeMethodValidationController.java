package com.company.fixtures.web;

import com.company.fixtures.web.ProbeRequest.ProbeItem;
import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint without {@code @Validated}: Spring MVC's built-in method validation applies
 * and fails with {@code HandlerMethodValidationException}.
 */
@RestController
public class ProbeMethodValidationController {

  private final ApiResponses responses;

  public ProbeMethodValidationController(ApiResponses responses) {
    this.responses = responses;
  }

  /** A constrained path variable plus a validated body: body errors arrive as ParameterErrors. */
  @PutMapping("/probe-mv/items/{id}")
  public ResponseEntity<ApiResponse<ProbeRequest>> update(
      @PathVariable @Min(1) long id, @Valid @RequestBody ProbeRequest request) {
    return responses.ok("Item updated", request);
  }

  /** A list body: each invalid element arrives as ParameterErrors with its container index. */
  @PostMapping("/probe-mv/items")
  public ResponseEntity<ApiResponse<Integer>> createAll(@RequestBody List<@Valid ProbeItem> items) {
    return responses.ok("Items accepted", items.size());
  }

  @GetMapping("/probe-mv/search")
  public ResponseEntity<ApiResponse<Integer>> search(@RequestParam @Min(1) int size) {
    return responses.ok("Searched", size);
  }
}
