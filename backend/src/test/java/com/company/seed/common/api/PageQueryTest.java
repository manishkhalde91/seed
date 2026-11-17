package com.company.seed.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.seed.common.api.PageQuery.InvalidPageQueryException;
import com.company.seed.common.api.PageQuery.Order;
import com.company.seed.common.api.PageQuery.Violation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The paging rules of api-standards.md §4, as {@link PageQuery#parse} applies them. */
class PageQueryTest {

  private static final Set<String> SORTABLE = Set.of("name", "createdAt");
  private static final List<Order> DEFAULT_SORT = List.of(Order.desc("createdAt"));

  @Test
  void missingParametersGiveTheDefaults() {
    PageQuery query = parse(null, null, List.of());

    assertThat(query).isEqualTo(new PageQuery(0, 20, DEFAULT_SORT));
    assertThat(parse("", " ", List.of())).isEqualTo(query);
  }

  @Test
  void sortsKeepTheirOrderAndDefaultToAscending() {
    PageQuery query = parse("2", "100", List.of(" name , asc ", "createdAt,DESC"));

    assertThat(query.page()).isEqualTo(2);
    assertThat(query.size()).isEqualTo(100);
    assertThat(query.offset()).isEqualTo(200);
    assertThat(query.sort()).containsExactly(Order.asc("name"), Order.desc("createdAt"));
    assertThat(parse(null, null, List.of("name")).sort()).containsExactly(Order.asc("name"));
  }

  @ParameterizedTest(name = "[{index}] page={0} size={1} sort={2}")
  @CsvSource({
    "-1, 20, name, page, Min",
    "x, 20, name, page, typeMismatch",
    "1.5, 20, name, page, typeMismatch",
    "99999999999, 20, name, page, typeMismatch",
    "0, 0, name, size, Min",
    "0, 101, name, size, Max",
    "0, abc, name, size, typeMismatch",
    "0, 20, description, sort, SortField",
    "0, 20, '', sort, SortField",
    "0, 20, 'name,up', sort, SortDirection",
    "0, 20, 'name,asc,desc', sort, SortDirection",
    "21474837, 100, name, page, Max"
  })
  void anInvalidParameterIsNamedWithItsRule(
      String page, String size, String sort, String parameter, String code) {
    assertThatThrownBy(() -> parse(page, size, List.of(sort)))
        .isInstanceOfSatisfying(
            InvalidPageQueryException.class,
            ex ->
                assertThat(ex.violations())
                    .extracting(Violation::parameter, Violation::code)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(parameter, code)));
  }

  @Test
  void everyInvalidParameterIsReportedAtOnce() {
    assertThatThrownBy(() -> parse("-1", "500", List.of("name", "secret")))
        .isInstanceOfSatisfying(
            InvalidPageQueryException.class,
            ex ->
                assertThat(ex.violations())
                    .extracting(Violation::parameter)
                    .containsExactly("page", "size", "sort"));
  }

  @Test
  void aRepeatedFieldAndMoreOrdersThanFieldsAreRejected() {
    assertThatThrownBy(() -> parse(null, null, List.of("name", "name,desc")))
        .isInstanceOfSatisfying(
            InvalidPageQueryException.class,
            ex ->
                assertThat(ex.violations())
                    .extracting(Violation::code)
                    .containsExactly("SortDuplicate"));
    assertThatThrownBy(() -> parse(null, null, List.of("name", "createdAt", "name")))
        .isInstanceOfSatisfying(
            InvalidPageQueryException.class,
            ex ->
                assertThat(ex.violations())
                    .extracting(Violation::code)
                    .containsExactly("SortCount"));
  }

  @Test
  void theLastPageBelowTheOffsetLimitIsAccepted() {
    int lastPage = (int) (PageQuery.MAX_OFFSET / PageQuery.MAX_SIZE);

    assertThat(parse(Integer.toString(lastPage), "100", List.of()).offset())
        .isLessThanOrEqualTo(PageQuery.MAX_OFFSET);
    assertThatThrownBy(() -> PageQuery.of(lastPage + 1, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEndpointWithoutSortableFieldsRejectsEverySort() {
    assertThatThrownBy(() -> PageQuery.parse(null, null, List.of("name"), Set.of(), List.of()))
        .isInstanceOf(InvalidPageQueryException.class);
  }

  @Test
  void aDefaultSortIsParsedFromConfiguration() {
    assertThat(PageQuery.parseOrder("createdAt,desc")).isEqualTo(Order.desc("createdAt"));
    assertThat(PageQuery.parseOrder("name")).isEqualTo(Order.asc("name"));
    assertThatThrownBy(() -> PageQuery.parseOrder("name,sideways"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PageQuery.parseOrder("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PageQuery.parseOrder(",desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aQueryOutsideTheLimitsCannotBeBuilt() {
    assertThatThrownBy(() -> PageQuery.of(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PageQuery.of(0, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  private static PageQuery parse(String page, String size, List<String> sort) {
    return PageQuery.parse(page, size, sort, SORTABLE, DEFAULT_SORT);
  }
}
