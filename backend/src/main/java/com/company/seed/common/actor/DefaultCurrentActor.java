package com.company.seed.common.actor;

import org.springframework.stereotype.Component;

/**
 * The fallback {@link CurrentActor}: the open {@link SystemActor} scope, otherwise {@value
 * CurrentActor#ANONYMOUS}. The security module's {@code @Primary} actor replaces it wherever a
 * security context exists, adding the authenticated token's {@code user:<sub>} between the two
 * (data-and-migrations.md §4).
 */
@Component
class DefaultCurrentActor implements CurrentActor {

  @Override
  public String id() {
    String scoped = SystemActor.current();
    return scoped != null ? scoped : ANONYMOUS;
  }
}
