package com.company.seed.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the roles of a token to authorities (security.md §2). The roles are the list of strings at
 * {@code seed.security.roles-claim} ({@code OIDC_ROLES_CLAIM}, default {@code roles}): the claim of
 * exactly that name when the token has one (so a URL-namespaced name such as {@code
 * https://example.com/roles} works), otherwise the dotted path, such as {@code realm_access.roles},
 * walked through nested objects. Each entry, trimmed and upper-cased, becomes {@code ROLE_<NAME>};
 * an entry already starting with {@code ROLE_} keeps its single prefix. A missing claim, a claim of
 * another shape, and blank or non-string entries give no authority.
 */
final class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  static final String ROLE_PREFIX = "ROLE_";

  private final String claim;
  private final List<String> path;

  JwtRoleConverter(String claim) {
    this.claim = claim;
    this.path = List.of(claim.split("\\.", -1));
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Object value = jwt.getClaims().containsKey(claim) ? jwt.getClaims().get(claim) : walk(jwt);
    if (!(value instanceof Collection<?> entries)) {
      return List.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object entry : entries) {
      if (entry instanceof String name && !name.isBlank()) {
        String upper = name.strip().toUpperCase(Locale.ROOT);
        names.add(upper.startsWith(ROLE_PREFIX) ? upper : ROLE_PREFIX + upper);
      }
    }
    List<GrantedAuthority> authorities = new ArrayList<>(names.size());
    names.forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
    return authorities;
  }

  private Object walk(Jwt jwt) {
    Object value = jwt.getClaims();
    for (String segment : path) {
      if (!(value instanceof Map<?, ?> object)) {
        return null;
      }
      value = object.get(segment);
    }
    return value;
  }
}
