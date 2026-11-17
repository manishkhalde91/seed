package com.company.fixtures.archunit.auditrequest.api;

import java.time.Instant;

/** Violates the audit-field rule: a client must never send who created a record. */
public record CreateAuditedRequest(String name, String createdBy, Instant createdAt) {}
