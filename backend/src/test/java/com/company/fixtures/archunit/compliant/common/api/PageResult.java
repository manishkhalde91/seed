package com.company.fixtures.archunit.compliant.common.api;

import java.util.List;

/** Stands in for the shared {@code common.api} paging contract. */
public record PageResult<T>(List<T> items, long total) {}
