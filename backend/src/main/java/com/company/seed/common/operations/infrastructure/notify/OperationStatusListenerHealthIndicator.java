package com.company.seed.common.operations.infrastructure.notify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code operationStatusListener} readiness contributor: UP while {@link PgStatusListener} is
 * listening, DOWN while its connection is down (it is reconnecting), so an instance that would miss
 * status changes takes no traffic.
 *
 * <p>It exists in every context, so the readiness group always names a known contributor; where no
 * web server runs (the {@code migrate} run) there is no listener and it reports UNKNOWN, which
 * never brings a group down.
 */
@Component
class OperationStatusListenerHealthIndicator implements HealthIndicator {

  private final ObjectProvider<PgStatusListener> listener;

  OperationStatusListenerHealthIndicator(ObjectProvider<PgStatusListener> listener) {
    this.listener = listener;
  }

  @Override
  public Health health() {
    PgStatusListener running = listener.getIfAvailable();
    if (running == null) {
      return Health.unknown().withDetail("reason", "No web server: nothing to push").build();
    }
    return running.isListening()
        ? Health.up().build()
        : Health.down().withDetail("reason", "Not listening; reconnecting").build();
  }
}
