package com.company.seed.feature.sample.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DEMO ONLY. Body of {@code POST /api/v1/sample-records}. A NUL character, which PostgreSQL cannot
 * store in text, fails {@code Pattern}. Rules mirrored by the frontend: docs/validation-rules.md.
 */
public record CreateSampleRecordRequest(
    @NotBlank @Size(max = 100) @Pattern(regexp = NO_NUL) String name,
    @Size(max = 1000) @Pattern(regexp = NO_NUL) String description) {

  /** Any text without a NUL character. */
  static final String NO_NUL = "[^\\u0000]*";
}
