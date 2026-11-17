package com.company.fixtures.archunit.auditrequest.api;

import java.time.Instant;

/** Not a request: a response may show audit fields. */
public record AuditView(String createdBy, Instant createdAt) {}
