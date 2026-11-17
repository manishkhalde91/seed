package com.company.seed.common.api;

import java.util.List;
import java.util.function.Function;

/**
 * One page of results as returned by application services; Spring Data {@code Page} never leaves
 * the repository implementations (api-standards.md §4).
 *
 * @param items the items of this page
 * @param page 0-based page index
 * @param size requested page size, at least 1
 * @param totalElements number of items across all pages
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

  public PageResult {
    if (items == null) {
      throw new IllegalArgumentException("items must not be null");
    }
    if (page < 0 || size < 1 || totalElements < 0) {
      throw new IllegalArgumentException("page must be >= 0, size >= 1 and totalElements >= 0");
    }
    items = List.copyOf(items);
  }

  /** The same page with every item mapped, e.g. from a domain object to its API response. */
  public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
    return new PageResult<>(items.stream().<R>map(mapper).toList(), page, size, totalElements);
  }

  public int totalPages() {
    return Math.toIntExact(totalElements / size + (totalElements % size == 0 ? 0 : 1));
  }

  public boolean hasNext() {
    return page + 1 < totalPages();
  }

  PageMeta toPageMeta() {
    return new PageMeta(page, size, totalElements, totalPages(), hasNext());
  }
}
