package com.company.seed.common.api;

/** Paging block of a paged {@link ApiResponse}. {@code page} is 0-based. */
public record PageMeta(int page, int size, long totalElements, int totalPages, boolean hasNext) {}
