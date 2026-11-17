package com.company.seed.common.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What page of a list a client asked for (api-standards.md §4): {@code page} (0-based, default
 * {@value #DEFAULT_PAGE}), {@code size} (1 to {@value #MAX_SIZE}, default {@value #DEFAULT_SIZE})
 * and the sort orders, in priority order. Controllers take it as a parameter (bound by {@link
 * PageQueryArgumentResolver}, with the endpoint's {@link Sortable} whitelist); services pass it to
 * repositories, which return a {@link PageResult}. Plain Java, so a domain repository interface may
 * use it.
 *
 * @param page 0-based page index, at least 0
 * @param size page size, 1 to {@value #MAX_SIZE}
 * @param sort the sort orders, first one first; may be empty
 */
public record PageQuery(int page, int size, List<Order> sort) {

  /** The query parameter of the page index. */
  public static final String PAGE = "page";

  /** The query parameter of the page size. */
  public static final String SIZE = "size";

  /** The repeatable query parameter of a sort order: {@code field[,asc|desc]}. */
  public static final String SORT = "sort";

  public static final int DEFAULT_PAGE = 0;
  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;

  /** The largest offset ({@code page × size}) a query may reach: the JPA first-result limit. */
  public static final long MAX_OFFSET = Integer.MAX_VALUE;

  /** At most 18 digits, so {@link Long#parseLong} cannot overflow before the range check. */
  private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?\\d{1,18}");

  public PageQuery {
    if (page < 0 || size < 1 || size > MAX_SIZE || (long) page * size > MAX_OFFSET) {
      throw new IllegalArgumentException(
          "page must be >= 0, size between 1 and "
              + MAX_SIZE
              + " and page × size at most "
              + MAX_OFFSET
              + ": "
              + page
              + ", "
              + size);
    }
    sort = List.copyOf(Objects.requireNonNull(sort, "sort"));
  }

  /** A query for {@code page} of {@code size} items, sorted by {@code sort}. */
  public static PageQuery of(int page, int size, Order... sort) {
    return new PageQuery(page, size, List.of(sort));
  }

  /** The index of the first item of this page. */
  public long offset() {
    return (long) page * size;
  }

  /** Sort direction; {@link #ASC} when a sort parameter names none. */
  public enum Direction {
    ASC,
    DESC
  }

  /** One sort order: a whitelisted API field name and its direction. */
  public record Order(String field, Direction direction) {

    public Order {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(direction, "direction");
    }

    public static Order asc(String field) {
      return new Order(field, Direction.ASC);
    }

    public static Order desc(String field) {
      return new Order(field, Direction.DESC);
    }
  }

  /**
   * One rejected query parameter.
   *
   * @param parameter {@value #PAGE}, {@value #SIZE} or {@value #SORT}
   * @param code the rule it broke: {@code typeMismatch}, {@code Min}, {@code Max}, {@code
   *     SortField}, {@code SortDirection}, {@code SortDuplicate} or {@code SortCount}
   * @param message a client-safe description
   * @param rejectedValue the value as sent
   */
  public record Violation(String parameter, String code, String message, String rejectedValue) {}

  /** The request's paging parameters break the rules; carries every violation found. */
  public static final class InvalidPageQueryException extends RuntimeException {

    private final transient List<Violation> violations;

    public InvalidPageQueryException(List<Violation> violations) {
      super("Invalid paging parameters: " + violations);
      this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
      return violations;
    }
  }

  /**
   * Parses the raw query parameters of a request.
   *
   * @param page the {@code page} value, or {@code null}/empty for the default
   * @param size the {@code size} value, or {@code null}/empty for the default
   * @param sort every {@code sort} value, in order; empty for {@code defaultSort}
   * @param sortableFields the fields this endpoint may sort by
   * @param defaultSort the orders used when the request sends no {@code sort}
   * @throws InvalidPageQueryException naming every invalid parameter
   */
  public static PageQuery parse(
      String page,
      String size,
      Collection<String> sort,
      Set<String> sortableFields,
      List<Order> defaultSort) {
    List<Violation> violations = new ArrayList<>();
    Integer pageValue = parseInt(PAGE, page, DEFAULT_PAGE, violations);
    if (pageValue != null && pageValue < 0) {
      violations.add(new Violation(PAGE, "Min", "must be at least 0", page));
    }
    Integer sizeValue = parseInt(SIZE, size, DEFAULT_SIZE, violations);
    if (sizeValue != null && sizeValue < 1) {
      violations.add(new Violation(SIZE, "Min", "must be at least 1", size));
    } else if (sizeValue != null && sizeValue > MAX_SIZE) {
      violations.add(new Violation(SIZE, "Max", "must be at most " + MAX_SIZE, size));
    } else if (pageValue != null
        && sizeValue != null
        && pageValue >= 0
        && (long) pageValue * sizeValue > MAX_OFFSET) {
      violations.add(new Violation(PAGE, "Max", "page × size must be at most " + MAX_OFFSET, page));
    }
    List<Order> orders = new ArrayList<>();
    if (sort == null || sort.isEmpty()) {
      orders.addAll(defaultSort);
    } else if (sort.size() > sortableFields.size()) {
      violations.add(
          new Violation(
              SORT,
              "SortCount",
              "at most " + sortableFields.size() + " sort orders",
              String.join("&", sort)));
    } else {
      Set<String> seen = new HashSet<>();
      for (String value : sort) {
        Order order = parseOrder(value, sortableFields, violations);
        if (order != null && !seen.add(order.field())) {
          violations.add(new Violation(SORT, "SortDuplicate", "sorts by a field twice", value));
        } else if (order != null) {
          orders.add(order);
        }
      }
    }
    if (!violations.isEmpty()) {
      throw new InvalidPageQueryException(violations);
    }
    return new PageQuery(pageValue, sizeValue, orders);
  }

  /**
   * Parses one {@code field[,asc|desc]} order of trusted configuration (an endpoint's default
   * sort).
   *
   * @throws IllegalArgumentException when it is malformed
   */
  public static Order parseOrder(String value) {
    List<Violation> violations = new ArrayList<>();
    String field = value == null ? "" : value.split(",", -1)[0].strip();
    Order order = field.isEmpty() ? null : parseOrder(value, Set.of(field), violations);
    if (order == null) {
      throw new IllegalArgumentException("Not a sort order (field[,asc|desc]): " + value);
    }
    return order;
  }

  private static Integer parseInt(
      String parameter, String value, int defaultValue, List<Violation> violations) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    String digits = value.strip();
    long number = WHOLE_NUMBER.matcher(digits).matches() ? Long.parseLong(digits) : Long.MAX_VALUE;
    if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
      violations.add(new Violation(parameter, "typeMismatch", "must be a whole number", value));
      return null;
    }
    return (int) number;
  }

  private static Order parseOrder(
      String value, Set<String> sortableFields, List<Violation> violations) {
    String[] parts = value == null ? new String[] {""} : value.split(",", -1);
    String field = parts[0].strip();
    if (!sortableFields.contains(field)) {
      String allowed =
          sortableFields.isEmpty()
              ? "this list cannot be sorted"
              : "must be one of " + String.join(", ", new TreeSet<>(sortableFields));
      violations.add(new Violation(SORT, "SortField", allowed, value));
      return null;
    }
    if (parts.length == 1) {
      return Order.asc(field);
    }
    String direction = parts[1].strip().toUpperCase(Locale.ROOT);
    if (parts.length > 2 || !(direction.equals("ASC") || direction.equals("DESC"))) {
      violations.add(new Violation(SORT, "SortDirection", "direction must be asc or desc", value));
      return null;
    }
    return new Order(field, Direction.valueOf(direction));
  }
}
