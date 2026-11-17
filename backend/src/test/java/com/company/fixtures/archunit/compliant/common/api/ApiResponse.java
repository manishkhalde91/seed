package com.company.fixtures.archunit.compliant.common.api;

/** Stands in for the shared {@code common.api} success envelope. */
public record ApiResponse<T>(boolean success, String message, T data) {}
