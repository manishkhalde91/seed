package com.company.fixtures.web;

/** Test-only: one item of {@link PagingProbeController}'s page, echoing the bound query. */
public record PagingProbeItem(int page, int size, String sort) {}
