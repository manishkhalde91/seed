package com.company.seed.feature.sample.domain;

import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * DEMO ONLY. 409 {@code INVALID_STATE_TRANSITION}: only a deleted sample record can be restored.
 */
public class SampleRecordNotDeletedException extends ConflictException {

  public SampleRecordNotDeletedException(UUID id) {
    super(
        ErrorCode.INVALID_STATE_TRANSITION,
        "Only a deleted sample record can be restored",
        Map.of("id", id));
  }
}
