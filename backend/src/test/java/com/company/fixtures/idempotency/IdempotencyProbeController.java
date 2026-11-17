package com.company.fixtures.idempotency;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.concurrent.Callable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints behind the idempotency filter. Each response carries the invocation number,
 * so two runs of the handler give different bodies.
 */
@RestController
@RequestMapping(IdempotencyProbeController.BASE)
public class IdempotencyProbeController {

  public static final String BASE = "/probe-idem";
  public static final int LARGE_SIZE = 2048;

  private final ApiResponses responses;
  private final IdempotencyProbe probe;

  public IdempotencyProbeController(ApiResponses responses, IdempotencyProbe probe) {
    this.responses = responses;
    this.probe = probe;
  }

  public record Item(@NotBlank String name) {}

  public record Result(int invocation, String name) {}

  @PostMapping("/items")
  public ResponseEntity<ApiResponse<Result>> create(@Valid @RequestBody Item item) {
    int invocation = probe.invocations();
    return responses.created(
        "Item created",
        new Result(invocation, item.name()),
        URI.create(BASE + "/items/" + invocation));
  }

  @GetMapping("/items")
  public ResponseEntity<ApiResponse<Integer>> list() {
    return responses.ok("Items retrieved", probe.invocations());
  }

  @PutMapping("/items/{id}")
  public ResponseEntity<ApiResponse<Result>> replace(
      @PathVariable String id, @Valid @RequestBody Item item) {
    return responses.ok("Item replaced", new Result(probe.invocations(), item.name()));
  }

  @PatchMapping("/items/{id}")
  public ResponseEntity<ApiResponse<Result>> patch(
      @PathVariable String id, @Valid @RequestBody Item item) {
    return responses.ok("Item patched", new Result(probe.invocations(), item.name()));
  }

  @DeleteMapping("/items/{id}")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
    return responses.deleted("Item deleted");
  }

  /** Only for ADMIN: a {@code USER} token is refused by method security, inside the handler. */
  @PostMapping("/admin")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).ADMIN)")
  public ResponseEntity<ApiResponse<Result>> adminOnly(@RequestBody Item item) {
    return responses.ok("Admin item", new Result(probe.invocations(), item.name()));
  }

  /** Waits inside the handler when the test armed {@link IdempotencyProbe#blockNext()}. */
  @PostMapping("/slow")
  public ResponseEntity<ApiResponse<Result>> slow(@RequestBody Item item)
      throws InterruptedException {
    int invocation = probe.invocations();
    probe.maybeBlock();
    return responses.created(
        "Item created",
        new Result(invocation, item.name()),
        URI.create(BASE + "/slow/" + invocation));
  }

  /** A form post: the parameter is parsed by the container from the request body. */
  @PostMapping("/form")
  public ResponseEntity<ApiResponse<String>> form(@RequestParam String name) {
    return responses.ok("Form read", name);
  }

  /**
   * A response body of over {@value #LARGE_SIZE} characters: larger than the tests' stored-response
   * cap, smaller than their request body limit.
   */
  @PostMapping("/large")
  public ResponseEntity<ApiResponse<Result>> large() {
    return responses.ok("Large", new Result(probe.invocations(), "x".repeat(LARGE_SIZE)));
  }

  /** An unexpected failure: GlobalExceptionHandler answers 500. */
  @PostMapping("/fail")
  public ResponseEntity<ApiResponse<Void>> fail() {
    throw new IllegalStateException("probe failure");
  }

  /** Async processing: the response is written by the ASYNC dispatch. */
  @PostMapping("/async")
  public Callable<ResponseEntity<ApiResponse<Result>>> async(@RequestBody Item item) {
    int invocation = probe.invocations();
    return () ->
        responses.created(
            "Item created",
            new Result(invocation, item.name()),
            URI.create(BASE + "/async/" + invocation));
  }
}
