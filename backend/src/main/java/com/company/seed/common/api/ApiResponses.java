package com.company.seed.common.api;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Builds every success response. Controllers return what these methods return; {@code timestamp}
 * comes from the injected {@link Clock} and {@code correlationId} from the MDC, never by hand.
 */
@Component
public class ApiResponses {

  /** MDC key that holds the request's correlation ID. */
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";

  private final Clock clock;

  public ApiResponses(Clock clock) {
    this.clock = clock;
  }

  /** 200 with {@code data}. */
  public <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
    return ResponseEntity.ok(body(message, data, null));
  }

  /** 201 with {@code data} and a {@code Location} header pointing at the new resource. */
  public <T> ResponseEntity<ApiResponse<T>> created(String message, T data, URI location) {
    return ResponseEntity.created(location).body(body(message, data, null));
  }

  /** 202 with the operation in {@code data} and a {@code Location} header pointing at it. */
  public <T> ResponseEntity<ApiResponse<T>> accepted(String message, T operation, URI location) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(location)
        .body(body(message, operation, null));
  }

  /** 200 with {@code data: null}, for a (soft) delete. */
  public ResponseEntity<ApiResponse<Void>> deleted(String message) {
    return ResponseEntity.ok(body(message, null, null));
  }

  /** 200 with the page items in {@code data} and the paging block in {@code pagination}. */
  public <T> ResponseEntity<ApiResponse<List<T>>> page(String message, PageResult<T> page) {
    return ResponseEntity.ok(body(message, page.items(), page.toPageMeta()));
  }

  private <T> ApiResponse<T> body(String message, T data, PageMeta pagination) {
    return new ApiResponse<>(
        true, message, data, clock.instant(), MDC.get(CORRELATION_ID_MDC_KEY), pagination);
  }
}
