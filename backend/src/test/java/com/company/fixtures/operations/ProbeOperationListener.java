package com.company.fixtures.operations;

import com.company.seed.common.operations.application.OperationListenerSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Propagation;

/**
 * Test-only: the async listener of a probe operation, run through {@link OperationListenerSupport}
 * like a feature's. In {@code THROW_FROM_LISTENER} mode its first delivery fails before the support
 * runs, so the publication stays incomplete until it is resubmitted.
 */
public class ProbeOperationListener {

  private final OperationListenerSupport support;
  private final ProbeOperationBehaviour behaviour;
  private final AtomicBoolean thrown = new AtomicBoolean();

  public ProbeOperationListener(
      OperationListenerSupport support, ProbeOperationBehaviour behaviour) {
    this.support = support;
    this.behaviour = behaviour;
  }

  public void reset() {
    thrown.set(false);
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(ProbeOperationRequested event) {
    if (behaviour.mode() == ProbeOperationBehaviour.Mode.THROW_FROM_LISTENER
        && thrown.compareAndSet(false, true)) {
      throw new IllegalStateException("probe listener down");
    }
    support.run(
        event.operationId(), event.requestedBy(), event.correlationId(), behaviour::attempt);
  }
}
