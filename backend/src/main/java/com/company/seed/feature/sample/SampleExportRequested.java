package com.company.seed.feature.sample;

import java.util.UUID;

/**
 * DEMO ONLY. Published when a sample export starts (realtime-and-storage.md §1.2), in the same
 * transaction as its {@code QUEUED} operation; {@code SampleExportListener} does the work after
 * commit. Carries the requester and correlation ID, because the listener may run after a restart,
 * when no request context exists.
 */
public record SampleExportRequested(UUID operationId, String requestedBy, String correlationId) {}
