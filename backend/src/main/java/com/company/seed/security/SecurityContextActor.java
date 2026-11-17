package com.company.seed.security;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.actor.SystemActor;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The effective {@link CurrentActor} (data-and-migrations.md §4): the open {@link SystemActor}
 * scope, then the authenticated token as {@code user:<sub>}, then {@value CurrentActor#ANONYMOUS}.
 * {@code @Primary}, so it replaces the common module's fallback that knows no users.
 */
@Component
@Primary
class SecurityContextActor implements CurrentActor {

  private static final String ADMIN_AUTHORITY = "ROLE_" + Roles.ADMIN;

  @Override
  public String id() {
    String scoped = SystemActor.current();
    if (scoped != null) {
      return scoped;
    }
    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
        && jwt.isAuthenticated()
        && jwt.getToken().getSubject() != null
        && !jwt.getToken().getSubject().isBlank()) {
      return USER_PREFIX + jwt.getToken().getSubject();
    }
    return ANONYMOUS;
  }

  @Override
  public boolean isAdmin() {
    if (SystemActor.current() != null) {
      return false;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
  }
}
