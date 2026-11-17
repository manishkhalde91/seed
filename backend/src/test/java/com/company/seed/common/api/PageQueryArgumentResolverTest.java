package com.company.seed.common.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** The startup check of a {@link Sortable} whitelist. */
class PageQueryArgumentResolverTest {

  static class Handlers {
    void valid(
        @Sortable(
                fields = {"name", "rank"},
                defaultSort = {"rank,desc", "name"})
            PageQuery q) {}

    void outsideFields(@Sortable(fields = "name", defaultSort = "rank,desc") PageQuery q) {}

    void unparseable(@Sortable(fields = "name", defaultSort = "name,sideways") PageQuery q) {}

    void emptyField(@Sortable(fields = "name", defaultSort = ",desc") PageQuery q) {}
  }

  @Test
  void aDefaultSortInsideTheWhitelistPasses() {
    assertThatCode(() -> PageQueryArgumentResolver.check(sortable("valid"), "valid"))
        .doesNotThrowAnyException();
  }

  @Test
  void aDefaultSortOutsideTheWhitelistOrMalformedFailsNamingTheHandler() {
    assertThatThrownBy(() -> PageQueryArgumentResolver.check(sortable("outsideFields"), "h1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("h1")
        .hasMessageContaining("'rank'");
    assertThatThrownBy(() -> PageQueryArgumentResolver.check(sortable("unparseable"), "h2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("name,sideways");
    assertThatThrownBy(() -> PageQueryArgumentResolver.check(sortable("emptyField"), "h3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(",desc");
  }

  private static Sortable sortable(String handler) throws NoSuchMethodException {
    Method method = Handlers.class.getDeclaredMethod(handler, PageQuery.class);
    return method.getParameters()[0].getAnnotation(Sortable.class);
  }
}
