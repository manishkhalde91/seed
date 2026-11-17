package com.company.fixtures.persistence;

import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.ids.IdGenerator;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that drive the persistence error rows through the real handler. */
@RestController
@RequestMapping("/probe-db/things")
public class AuditedThingProbeController {

  private final AuditedThingRepository repository;
  private final IdGenerator ids;
  private final ApiResponses responses;

  public AuditedThingProbeController(
      AuditedThingRepository repository, IdGenerator ids, ApiResponses responses) {
    this.repository = repository;
    this.ids = ids;
    this.responses = responses;
  }

  /** Inserts a thing; a duplicate name or a negative quantity violates a database constraint. */
  @PostMapping
  public ResponseEntity<ApiResponse<UUID>> create(
      @RequestParam String name, @RequestParam(defaultValue = "0") int quantity) {
    AuditedThing thing = repository.saveAndFlush(new AuditedThing(ids.newId(), name, quantity));
    return responses.ok("Thing created", thing.getId());
  }

  /** Inserts a thing inside the {@code system:x} scope, which wins over the request's user. */
  @PostMapping("/system-scoped")
  public ResponseEntity<ApiResponse<UUID>> createInSystemScope(@RequestParam String name) {
    AuditedThing thing =
        SystemActor.call(
            "system:x", () -> repository.saveAndFlush(new AuditedThing(ids.newId(), name, 0)));
    return responses.ok("Thing created", thing.getId());
  }

  @PostMapping("/{id}/restore")
  public ResponseEntity<ApiResponse<Boolean>> restore(@PathVariable UUID id) {
    return responses.ok("Thing restored", repository.restore(id));
  }

  /** Saves a copy read before another save changed the row: its version is stale. */
  @PostMapping("/{id}/stale-save")
  public ResponseEntity<ApiResponse<Long>> staleSave(@PathVariable UUID id) {
    AuditedThing stale = repository.findById(id).orElseThrow();
    AuditedThing fresh = repository.findById(id).orElseThrow();
    fresh.rename(fresh.getName() + "-fresh");
    repository.saveAndFlush(fresh);
    stale.rename(stale.getName() + "-stale");
    return responses.ok("Thing saved", repository.saveAndFlush(stale).getVersion());
  }
}
