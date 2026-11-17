package com.company.seed.common.operations.api;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.operations.application.OperationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST snapshot of an operation (api-standards.md §10): for its requester or an {@code ADMIN};
 * another {@code USER} gets 403 and an unknown ID 404. The live status is pushed over STOMP to
 * {@code statusTopic} ({@link OperationStatusSubscriptionController} answers the STOMP snapshot);
 * this is the REST fallback.
 */
@RestController
@RequestMapping(OperationController.BASE)
public class OperationController {

  static final String BASE = "/api/v1/operations";

  private final OperationService operations;
  private final ApiResponses responses;

  public OperationController(OperationService operations, ApiResponses responses) {
    this.operations = operations;
    this.responses = responses;
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "getOperation")
  public ResponseEntity<ApiResponse<OperationResponse>> get(@PathVariable UUID id) {
    return responses.ok(
        "Operation retrieved", OperationResponse.of(operations.getForCurrentActor(id)));
  }
}
