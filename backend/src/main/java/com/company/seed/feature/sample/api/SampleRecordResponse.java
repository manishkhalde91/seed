package com.company.seed.feature.sample.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * DEMO ONLY. A sample record as the API returns it; {@code version} goes back in the next PUT.
 * Every member is always written; {@code description} may be {@code null}.
 */
@Schema(
    requiredProperties = {
      "id",
      "name",
      "description",
      "version",
      "createdAt",
      "createdBy",
      "updatedAt",
      "updatedBy"
    })
public record SampleRecordResponse(
    UUID id,
    String name,
    @Schema(types = {"string", "null"}) String description,
    long version,
    Instant createdAt,
    String createdBy,
    Instant updatedAt,
    String updatedBy) {}
