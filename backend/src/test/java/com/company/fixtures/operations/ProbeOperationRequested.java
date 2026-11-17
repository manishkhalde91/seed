package com.company.fixtures.operations;

import java.util.UUID;

/** Test-only event: starts the work of a probe operation, like a feature's own event. */
public record ProbeOperationRequested(UUID operationId, String requestedBy, String correlationId) {}
