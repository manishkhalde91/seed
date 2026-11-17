package com.company.seed.common.api;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Binds a controller's {@link PageQuery} parameter from {@code page}, {@code size} and the
 * repeatable {@code sort}, checked against the parameter's {@link Sortable} whitelist. Invalid
 * values are a {@link MethodArgumentNotValidException}, so the client gets 400 {@code
 * VALIDATION_FAILED} with one {@code errors[]} entry per invalid parameter ({@code page}, {@code
 * size} or {@code sort}).
 */
public final class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String OBJECT_NAME = "pageQuery";

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return PageQuery.class.equals(parameter.getParameterType());
  }

  @Override
  public PageQuery resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory)
      throws MethodArgumentNotValidException {
    Sortable sortable = parameter.getParameterAnnotation(Sortable.class);
    Set<String> fields = sortable == null ? Set.of() : Set.of(sortable.fields());
    List<PageQuery.Order> defaultSort =
        sortable == null
            ? List.of()
            : Arrays.stream(sortable.defaultSort()).map(PageQuery::parseOrder).toList();
    String[] sort = webRequest.getParameterValues(PageQuery.SORT);
    try {
      return PageQuery.parse(
          webRequest.getParameter(PageQuery.PAGE),
          webRequest.getParameter(PageQuery.SIZE),
          sort == null ? List.of() : List.of(sort),
          fields,
          defaultSort);
    } catch (PageQuery.InvalidPageQueryException ex) {
      throw new MethodArgumentNotValidException(parameter, bindingResult(ex));
    }
  }

  /**
   * Checks an endpoint's whitelist: every default sort order parses and names a sortable field.
   *
   * @param where the handler, named in the message
   * @throws IllegalStateException naming the handler and the bad order
   */
  static void check(Sortable sortable, String where) {
    Set<String> fields = Set.of(sortable.fields());
    for (String value : sortable.defaultSort()) {
      PageQuery.Order order;
      try {
        order = PageQuery.parseOrder(value);
      } catch (IllegalArgumentException ex) {
        throw new IllegalStateException(
            "@Sortable on " + where + ": default sort '" + value + "' is not field[,asc|desc]", ex);
      }
      if (!fields.contains(order.field())) {
        throw new IllegalStateException(
            "@Sortable on "
                + where
                + ": default sort field '"
                + order.field()
                + "' is not one of "
                + fields);
      }
    }
  }

  private static BindingResult bindingResult(PageQuery.InvalidPageQueryException ex) {
    BindingResult result = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
    for (PageQuery.Violation violation : ex.violations()) {
      result.addError(
          new FieldError(
              OBJECT_NAME,
              violation.parameter(),
              violation.rejectedValue(),
              true,
              new String[] {violation.code()},
              null,
              violation.message()));
    }
    return result;
  }
}
