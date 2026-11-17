package com.company.seed.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Success envelope for every REST response (api-standards.md §3). Built only through {@link
 * ApiResponses}; {@code data} is always written (null allowed), {@code pagination} only on paged
 * responses.
 */
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    Instant timestamp,
    String correlationId,
    @JsonInclude(JsonInclude.Include.NON_NULL) PageMeta pagination) {}
