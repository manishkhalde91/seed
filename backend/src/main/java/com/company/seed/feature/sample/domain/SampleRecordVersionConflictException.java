package com.company.seed.feature.sample.domain;

import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DEMO ONLY. 409 {@code CONCURRENT_MODIFICATION}: the client changed a copy older than the stored
 * record.
 */
public class SampleRecordVersionConflictException extends ConflictException {

  public SampleRecordVersionConflictException(UUID id, long expectedVersion, Long storedVersion) {
    super(
        ErrorCode.CONCURRENT_MODIFICATION,
        "The sample record was changed by someone else; reload it and try again",
        details(id, expectedVersion, storedVersion));
  }

  private static Map<String, Object> details(UUID id, long expectedVersion, Long storedVersion) {
    Map<String, Object> details = new HashMap<>();
    details.put("id", id);
    details.put("expectedVersion", expectedVersion);
    details.put("storedVersion", storedVersion);
    return details;
  }
}
