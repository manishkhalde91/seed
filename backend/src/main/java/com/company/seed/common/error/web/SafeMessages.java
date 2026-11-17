package com.company.seed.common.error.web;

/** The fixed, client-safe {@code message} texts of the generic errors. */
final class SafeMessages {

  static final String VALIDATION_FAILED = "Request validation failed";
  static final String MALFORMED_REQUEST = "Malformed request";
  static final String UNAUTHENTICATED = "Authentication required";
  static final String FORBIDDEN = "Access denied";
  static final String NOT_FOUND = "Resource not found";
  static final String CONCURRENT_MODIFICATION =
      "The resource was changed by someone else; reload it and try again";
  static final String CONFLICTS_WITH_EXISTING_DATA = "Conflicts with existing data";
  static final String METHOD_NOT_ALLOWED = "Method not allowed";
  static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";
  static final String PAYLOAD_TOO_LARGE = "Payload too large";
  static final String RATE_LIMITED = "Too many requests";
  static final String SERVICE_UNAVAILABLE = "Service unavailable";
  static final String EXTERNAL_SERVICE_ERROR = "A dependency failed";
  static final String EXTERNAL_SERVICE_TIMEOUT = "A dependency timed out";
  static final String INVALID_VALUE = "Invalid value";
  static final String INTERNAL_ERROR = "Unexpected error. Quote the correlation ID.";

  private SafeMessages() {}
}
