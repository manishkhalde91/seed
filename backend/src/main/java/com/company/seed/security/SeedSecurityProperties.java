package com.company.seed.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seed.security.*} (security.md §1, §4), set from the environment in {@code
 * application.yml}. The issuer is Spring Boot's own {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} ({@code OIDC_ISSUER_URI}), checked by
 * {@link SecurityConfig}. Startup fails on an invalid value, naming the property.
 *
 * @param audience the audience every token must contain ({@code OIDC_AUDIENCE}); required
 * @param rolesClaim the claim holding the roles ({@code OIDC_ROLES_CLAIM}, default {@code roles});
 *     a dotted path such as {@code realm_access.roles} walks nested objects
 * @param corsAllowedOrigins the origins allowed to call the API from a browser ({@code
 *     CORS_ALLOWED_ORIGINS}, comma-separated); none by default. Each is exactly {@code
 *     scheme://host[:port]} (http or https, no path, no trailing slash): never a wildcard or {@code
 *     null}, which a browser would send and Spring would never match
 */
@ConfigurationProperties("seed.security")
public record SeedSecurityProperties(
    String audience,
    @DefaultValue("roles") String rolesClaim,
    @DefaultValue List<String> corsAllowedOrigins) {

  public SeedSecurityProperties {
    if (audience == null || audience.isBlank()) {
      throw new IllegalArgumentException(
          "seed.security.audience (OIDC_AUDIENCE) must be set: the audience every access token"
              + " must contain");
    }
    audience = audience.strip();
    if (rolesClaim == null
        || rolesClaim.isBlank()
        || List.of(rolesClaim.strip().split("\\.", -1)).stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(
          "seed.security.roles-claim (OIDC_ROLES_CLAIM) must be a claim name or a dotted path such"
              + " as realm_access.roles");
    }
    rolesClaim = rolesClaim.strip();
    corsAllowedOrigins =
        corsAllowedOrigins == null
            ? List.of()
            : corsAllowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::strip)
                .toList();
    corsAllowedOrigins.forEach(SeedSecurityProperties::requireOrigin);
  }

  /** Accepts {@code http(s)://host[:port]} and nothing else, naming the property otherwise. */
  private static void requireOrigin(String origin) {
    String problem =
        "seed.security.cors-allowed-origins (CORS_ALLOWED_ORIGINS) lists exact origins of the form"
            + " scheme://host[:port], without a path, wildcard or null; not: "
            + origin;
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException(problem, ex);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean valid =
        !origin.contains("*")
            && (scheme.equals("http") || scheme.equals("https"))
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
    if (!valid) {
      throw new IllegalArgumentException(problem);
    }
  }
}
