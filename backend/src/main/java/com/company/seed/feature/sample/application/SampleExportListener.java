package com.company.seed.feature.sample.application;

import com.company.seed.common.operations.application.OperationListenerSupport;
import com.company.seed.feature.sample.SampleExportRequested;
import com.company.seed.feature.sample.domain.SampleRecord;
import com.company.seed.feature.sample.domain.SampleRecordRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * DEMO ONLY. Does a sample export after its request commits (realtime-and-storage.md §1.2): builds
 * the CSV of every active record in memory and completes the operation with {@code {rowCount}}.
 * Story 10 stores the file and adds its {@code artifactId}.
 */
@Component
class SampleExportListener {

  private static final Logger LOG = LoggerFactory.getLogger(SampleExportListener.class);

  private final OperationListenerSupport operations;
  private final SampleRecordRepository repository;

  SampleExportListener(OperationListenerSupport operations, SampleRecordRepository repository) {
    this.operations = operations;
    this.repository = repository;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(SampleExportRequested event) {
    operations.run(
        event.operationId(),
        event.requestedBy(),
        event.correlationId(),
        () -> {
          List<SampleRecord> records = repository.findAllActive();
          String csv = SampleRecordCsv.of(records);
          LOG.info("Sample export built {} row(s), {} character(s)", records.size(), csv.length());
          return Map.of("rowCount", records.size());
        });
  }
}
