package com.company.seed.feature.sample.api;

import com.company.seed.feature.sample.domain.SampleRecord;

/** DEMO ONLY. Maps the sample aggregate to its API response. */
final class SampleRecordWebMapper {

  private SampleRecordWebMapper() {}

  static SampleRecordResponse toResponse(SampleRecord sampleRecord) {
    return new SampleRecordResponse(
        sampleRecord.id(),
        sampleRecord.name(),
        sampleRecord.description(),
        sampleRecord.version(),
        sampleRecord.createdAt(),
        sampleRecord.createdBy(),
        sampleRecord.updatedAt(),
        sampleRecord.updatedBy());
  }
}
