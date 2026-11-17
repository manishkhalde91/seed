package com.company.seed.common.error.web;

import com.company.seed.common.error.ErrorCode;
import org.slf4j.event.Level;

/**
 * Error code, safe message and log level for a bare HTTP status: a status that reached {@code
 * /error}, or a Spring MVC {@code ErrorResponse} outside the catalogue. Shared by {@link
 * GlobalExceptionHandler} and {@link JsonErrorController} so both answer a status the same way.
 */
record StatusMapping(String errorCode, String message, Level level) {

  static StatusMapping of(int status) {
    return switch (status) {
      case 400 ->
          new StatusMapping(
              ErrorCode.MALFORMED_REQUEST, SafeMessages.MALFORMED_REQUEST, Level.DEBUG);
      case 401 ->
          new StatusMapping(ErrorCode.UNAUTHENTICATED, SafeMessages.UNAUTHENTICATED, Level.INFO);
      case 403 -> new StatusMapping(ErrorCode.FORBIDDEN, SafeMessages.FORBIDDEN, Level.WARN);
      case 404 -> new StatusMapping(ErrorCode.NOT_FOUND, SafeMessages.NOT_FOUND, Level.DEBUG);
      case 405 ->
          new StatusMapping(
              ErrorCode.METHOD_NOT_ALLOWED, SafeMessages.METHOD_NOT_ALLOWED, Level.DEBUG);
      case 413 ->
          new StatusMapping(
              ErrorCode.PAYLOAD_TOO_LARGE, SafeMessages.PAYLOAD_TOO_LARGE, Level.INFO);
      case 415 ->
          new StatusMapping(
              ErrorCode.UNSUPPORTED_MEDIA_TYPE, SafeMessages.UNSUPPORTED_MEDIA_TYPE, Level.DEBUG);
      case 429 -> new StatusMapping(ErrorCode.RATE_LIMITED, SafeMessages.RATE_LIMITED, Level.INFO);
      case 502 ->
          new StatusMapping(
              ErrorCode.EXTERNAL_SERVICE_ERROR, SafeMessages.EXTERNAL_SERVICE_ERROR, Level.ERROR);
      case 503 ->
          new StatusMapping(
              ErrorCode.SERVICE_UNAVAILABLE, SafeMessages.SERVICE_UNAVAILABLE, Level.ERROR);
      case 504 ->
          new StatusMapping(
              ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
              SafeMessages.EXTERNAL_SERVICE_TIMEOUT,
              Level.ERROR);
      default ->
          status >= 400 && status < 500
              ? new StatusMapping(
                  ErrorCode.MALFORMED_REQUEST, SafeMessages.MALFORMED_REQUEST, Level.DEBUG)
              : new StatusMapping(
                  ErrorCode.INTERNAL_ERROR, SafeMessages.INTERNAL_ERROR, Level.ERROR);
    };
  }
}
