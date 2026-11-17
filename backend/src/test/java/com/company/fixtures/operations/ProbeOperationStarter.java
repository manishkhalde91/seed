package com.company.fixtures.operations;

import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.Operation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** Test-only: starts a probe operation and publishes its event in one transaction. */
public class ProbeOperationStarter {

  public static final String KIND = "PROBE";

  private final OperationService operations;
  private final ApplicationEventPublisher events;

  public ProbeOperationStarter(OperationService operations, ApplicationEventPublisher events) {
    this.operations = operations;
    this.events = events;
  }

  @Transactional
  public Operation start() {
    Operation operation = operations.start(KIND, null);
    events.publishEvent(
        new ProbeOperationRequested(
            operation.id(), operation.requestedBy(), operation.correlationId()));
    return operation;
  }
}
