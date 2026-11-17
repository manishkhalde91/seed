package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleConverterTest {

  @Test
  void eachRoleOfTheTopLevelClaimBecomesAnUpperCasedAuthority() {
    assertThat(names(new JwtRoleConverter("roles"), Map.of("roles", List.of("user", " Admin "))))
        .containsExactly("ROLE_USER", "ROLE_ADMIN");
  }

  @Test
  void aConfiguredClaimNameIsUsed() {
    assertThat(names(new JwtRoleConverter("groups"), Map.of("groups", List.of("ADMIN"))))
        .containsExactly("ROLE_ADMIN");
    assertThat(names(new JwtRoleConverter("groups"), Map.of("roles", List.of("ADMIN")))).isEmpty();
  }

  @Test
  void aDottedPathWalksNestedObjects() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");

    assertThat(names(converter, Map.of("realm_access", Map.of("roles", List.of("admin")))))
        .containsExactly("ROLE_ADMIN");
    assertThat(names(converter, Map.of("realm_access", List.of("admin")))).isEmpty();
  }

  @Test
  void aUrlNamespacedClaimNameIsReadAsOneName() {
    JwtRoleConverter converter = new JwtRoleConverter("https://example.com/roles");

    assertThat(names(converter, Map.of("https://example.com/roles", List.of("admin"))))
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void theExactClaimNameWinsOverTheDottedPath() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");

    assertThat(
            names(
                converter,
                Map.of(
                    "realm_access.roles", List.of("user"),
                    "realm_access", Map.of("roles", List.of("admin")))))
        .containsExactly("ROLE_USER");
  }

  @Test
  void anEntryAlreadyPrefixedKeepsOnePrefix() {
    assertThat(
            names(
                new JwtRoleConverter("roles"), Map.of("roles", List.of("ROLE_ADMIN", "role_user"))))
        .containsExactly("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void anythingButAListOfStringsGivesNoAuthority() {
    JwtRoleConverter converter = new JwtRoleConverter("roles");

    assertThat(names(converter, Map.of())).isEmpty();
    assertThat(names(converter, Map.of("roles", "ADMIN"))).isEmpty();
    assertThat(names(converter, Map.of("roles", List.of(1, "", " ", "user", "USER"))))
        .containsExactly("ROLE_USER");
  }

  private static List<String> names(JwtRoleConverter converter, Map<String, Object> claims) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("s")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    claims.forEach(jwt::claim);
    Collection<GrantedAuthority> authorities = converter.convert(jwt.build());
    return authorities.stream().map(GrantedAuthority::getAuthority).toList();
  }
}
