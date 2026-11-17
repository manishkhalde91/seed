package com.company.seed.common.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The sort whitelist of one paged endpoint, on its {@link PageQuery} parameter (api-standards.md
 * §4). A {@code sort} naming any other field is 400 {@code VALIDATION_FAILED}. Without this
 * annotation the list cannot be sorted by the client.
 *
 * <pre>{@code
 * @Sortable(fields = {"name", "createdAt"}, defaultSort = "createdAt,desc") PageQuery query
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Sortable {

  /** The API field names a client may sort by. */
  String[] fields();

  /** The orders used when the request sends no {@code sort}, each {@code field[,asc|desc]}. */
  String[] defaultSort() default {};
}
