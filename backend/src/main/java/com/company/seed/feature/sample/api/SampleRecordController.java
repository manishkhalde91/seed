package com.company.seed.feature.sample.api;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.Sortable;
import com.company.seed.common.operations.api.OperationAcceptedResponse;
import com.company.seed.feature.sample.application.SampleRecordService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The sample records over REST (api-standards.md §10): binding, validation, role checks
 * and envelopes only; every rule lives in {@link SampleRecordService}. {@code USER} reads, creates
 * and updates; {@code ADMIN} (which implies {@code USER}) also deletes and restores.
 */
@RestController
@RequestMapping(SampleRecordController.BASE)
@Validated
public class SampleRecordController {

  static final String BASE = "/api/v1/sample-records";

  private final SampleRecordService service;
  private final ApiResponses responses;

  public SampleRecordController(SampleRecordService service, ApiResponses responses) {
    this.service = service;
    this.responses = responses;
  }

  @GetMapping
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "listSampleRecords")
  public ResponseEntity<ApiResponse<List<SampleRecordResponse>>> list(
      @Sortable(
              fields = {"name", "createdAt"},
              defaultSort = "createdAt,desc")
          PageQuery query) {
    return responses.page(
        "Records retrieved", service.list(query).map(SampleRecordWebMapper::toResponse));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "getSampleRecord")
  public ResponseEntity<ApiResponse<SampleRecordResponse>> get(@PathVariable UUID id) {
    return responses.ok(
        "Sample record retrieved", SampleRecordWebMapper.toResponse(service.get(id)));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "createSampleRecord")
  public ResponseEntity<ApiResponse<SampleRecordResponse>> create(
      @Valid @RequestBody CreateSampleRecordRequest request) {
    SampleRecordResponse created =
        SampleRecordWebMapper.toResponse(service.create(request.name(), request.description()));
    return responses.created(
        "Sample record created", created, URI.create(BASE + "/" + created.id()));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "updateSampleRecord")
  public ResponseEntity<ApiResponse<SampleRecordResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateSampleRecordRequest request) {
    return responses.ok(
        "Sample record updated",
        SampleRecordWebMapper.toResponse(
            service.update(id, request.name(), request.description(), request.version())));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).ADMIN)")
  @Operation(operationId = "deleteSampleRecord")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    service.delete(id);
    return responses.deleted("Sample record deleted");
  }

  @PostMapping("/export")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  @Operation(operationId = "startSampleRecordExport")
  public ResponseEntity<ApiResponse<OperationAcceptedResponse>> export() {
    OperationAcceptedResponse accepted = OperationAcceptedResponse.of(service.startExport());
    return responses.accepted(
        "Export started",
        accepted,
        URI.create(OperationAcceptedResponse.location(accepted.operationId())));
  }

  @PostMapping("/{id}/restore")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).ADMIN)")
  @Operation(operationId = "restoreSampleRecord")
  public ResponseEntity<ApiResponse<SampleRecordResponse>> restore(@PathVariable UUID id) {
    return responses.ok(
        "Sample record restored", SampleRecordWebMapper.toResponse(service.restore(id)));
  }
}
