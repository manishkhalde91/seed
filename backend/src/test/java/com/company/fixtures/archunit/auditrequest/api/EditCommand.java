package com.company.fixtures.archunit.auditrequest.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Violates the audit-field rule under another Java name: the JSON member is updatedBy. */
public class EditCommand {

  @JsonProperty("updatedBy")
  private String editor;

  public String getEditor() {
    return editor;
  }
}
