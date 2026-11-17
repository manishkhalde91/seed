package com.company.seed.feature.sample.domain;

/** DEMO ONLY. The sample module's own error codes (api-standards.md §2). */
public final class SampleRecordErrors {

  /** 404: the record is missing or soft-deleted. */
  public static final String SAMPLE_RECORD_NOT_FOUND = "SAMPLE_RECORD_NOT_FOUND";

  /** 409: an active record already has the name. */
  public static final String SAMPLE_RECORD_ALREADY_EXISTS = "SAMPLE_RECORD_ALREADY_EXISTS";

  private SampleRecordErrors() {}
}
