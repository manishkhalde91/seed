package com.company.seed.common.actor;

/**
 * Who is acting right now, as written to {@code created_by}, {@code updated_by} and {@code
 * deleted_by}: {@code system:<name>} for work the system does on its own, {@code user:<sub>} for a
 * user, or {@code anonymous} (data-and-migrations.md §4).
 *
 * <p>Resolution order: the open {@link SystemActor} scope, then the authenticated token's {@code
 * user:<sub>}, then {@value #ANONYMOUS}. The security module's implementation is the effective one;
 * {@link DefaultCurrentActor} only covers contexts without it.
 */
public interface CurrentActor {

  /** The actor of a request that carries no authenticated user and opened no scope. */
  String ANONYMOUS = "anonymous";

  /** The prefix of a user actor; the rest is the token's {@code sub}. */
  String USER_PREFIX = SystemActor.USER_PREFIX;

  /**
   * The MDC key carrying the actor on every log line (errors-and-logging.md §4): the token's {@code
   * sub} during an authenticated request, the scope's actor inside a {@link SystemActor} scope (the
   * bare subject of a {@code user:} scope), absent otherwise. Cleared with the request's other MDC
   * keys.
   */
  String MDC_KEY = "user.id";

  /** The acting actor's ID; never {@code null}. */
  String id();

  /**
   * Whether the current actor holds the administrator role (security.md §3): only an authenticated
   * request can; work in a {@link SystemActor} scope never does. Lets common code allow ADMIN
   * without knowing how roles are named.
   */
  default boolean isAdmin() {
    return false;
  }
}
