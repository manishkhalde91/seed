package com.company.seed.feature.sample.domain;

import com.company.seed.common.error.ResourceNotFoundException;
import java.util.Map;
import java.util.UUID;

/** DEMO ONLY. 404 {@code SAMPLE_RECORD_NOT_FOUND}: no active sample record has the ID. */
public class SampleRecordNotFoundException extends ResourceNotFoundException {

  public SampleRecordNotFoundException(UUID id) {
    super(SampleRecordErrors.SAMPLE_RECORD_NOT_FOUND, "Sample record not found", Map.of("id", id));
  }
}
