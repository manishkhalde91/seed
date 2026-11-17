package com.company.fixtures.archunit.auditrequest.api;

import org.springframework.web.bind.annotation.RequestBody;

/** Takes request bodies with and without audit fields; returns one only as a view. */
public class AuditedThingController {

  public AuditView create(@RequestBody CreateAuditedRequest request) {
    return new AuditView(request.createdBy(), request.createdAt());
  }

  public void rename(@RequestBody RenameRequest request) {}

  public void edit(@RequestBody EditCommand command) {}
}
