package com.company.seed.common.error;

import java.util.Map;

/** 422: a well-formed request that breaks a domain rule. Code: {@code <MODULE>_<RULE>}. */
public class BusinessRuleException extends SeedException {

  public BusinessRuleException(String errorCode, String safeMessage) {
    this(errorCode, safeMessage, Map.of());
  }

  public BusinessRuleException(String errorCode, String safeMessage, Map<String, Object> details) {
    super(errorCode, 422, safeMessage, details, false, null);
  }
}
