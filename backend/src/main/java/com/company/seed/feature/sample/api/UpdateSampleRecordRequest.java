package com.company.seed.feature.sample.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * DEMO ONLY. Body of {@code PUT /api/v1/sample-records/{id}}: a full replace, so an omitted or
 * {@code null} description clears it. {@code version} is the one the client read. Rules mirrored by
 * the frontend: docs/validation-rules.md.
 */
public record UpdateSampleRecordRequest(
    @NotBlank @Size(max = 100) @Pattern(regexp = CreateSampleRecordRequest.NO_NUL) String name,
    @Size(max = 1000) @Pattern(regexp = CreateSampleRecordRequest.NO_NUL) String description,
    @NotNull @PositiveOrZero Long version) {}
