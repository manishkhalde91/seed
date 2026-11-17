package com.company.seed.security;

/**
 * The seed's role names (security.md §2), the only place they are written. Tokens carry them in the
 * roles claim; {@link JwtRoleConverter} maps each to the authority {@code ROLE_<NAME>}, and {@code
 * ADMIN} implies {@code USER}. Handlers refer to them through SpEL:
 *
 * <pre>{@code
 * @PreAuthorize("hasRole(T(com.company.seed.security.Roles).ADMIN)")
 * }</pre>
 *
 * Real projects replace them with capability-based authorities.
 */
public final class Roles {

  /** Any signed-in user of the application. */
  public static final String USER = "USER";

  /** An administrator; implies {@link #USER}. */
  public static final String ADMIN = "ADMIN";

  private Roles() {}
}
