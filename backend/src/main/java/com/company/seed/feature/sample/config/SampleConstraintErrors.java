package com.company.seed.feature.sample.config;

import com.company.seed.common.error.ConstraintErrorMapping;
import com.company.seed.feature.sample.domain.SampleRecordErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DEMO ONLY. A second active record with the same name violates the partial unique index {@value
 * #UNIQUE_NAME}: 409 {@code SAMPLE_RECORD_ALREADY_EXISTS} on create, update and restore.
 */
@Configuration(proxyBeanMethods = false)
class SampleConstraintErrors {

  static final String UNIQUE_NAME = "uq_sample_record__name";

  @Bean
  ConstraintErrorMapping sampleRecordConstraints() {
    return ConstraintErrorMapping.of(UNIQUE_NAME, SampleRecordErrors.SAMPLE_RECORD_ALREADY_EXISTS);
  }
}
