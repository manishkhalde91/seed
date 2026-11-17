package com.company.seed.common.operations.application.port;

import com.company.seed.common.operations.domain.Operation;

/**
 * Tells every instance that an operation's status changed (realtime-and-storage.md §1.2):
 * implemented with {@code pg_notify('operation_status', …)}, sent in the caller's transaction, so
 * it is delivered only when the change commits.
 */
public interface OperationStatusNotifier {

  void statusChanged(Operation operation);
}
