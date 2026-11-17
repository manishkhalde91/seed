package com.company.seed.security.api;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}: who the caller is and which roles they hold (api-standards.md §10), so
 * the frontend can greet the user and gate its features on {@code roles}. Only in a servlet web
 * application, like the security configuration whose role hierarchy it reads.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(MeController.PATH)
public class MeController {

  static final String PATH = "/api/v1/me";

  private static final String ROLE_PREFIX = "ROLE_";
  private static final String NAME_CLAIM = "name";
  private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

  private final ApiResponses responses;
  private final RoleHierarchy roleHierarchy;

  public MeController(ApiResponses responses, RoleHierarchy roleHierarchy) {
    this.responses = responses;
    this.roleHierarchy = roleHierarchy;
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "getCurrentUser")
  public ResponseEntity<ApiResponse<MeResponse>> me() {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken authentication)) {
      // Only a bearer token names a caller; anything else is answered as unauthenticated (401).
      throw new AuthenticationCredentialsNotFoundException("No JWT authentication");
    }
    Jwt token = authentication.getToken();
    return responses.ok(
        "Current user",
        new MeResponse(token.getSubject(), name(token), roles(authentication.getAuthorities())));
  }

  private static String name(Jwt token) {
    for (String claim : List.of(NAME_CLAIM, PREFERRED_USERNAME_CLAIM)) {
      if (token.getClaims().get(claim) instanceof String value && !value.isBlank()) {
        return value;
      }
    }
    return token.getSubject();
  }

  private List<String> roles(Collection<? extends GrantedAuthority> authorities) {
    return roleHierarchy.getReachableGrantedAuthorities(authorities).stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
        .map(authority -> authority.substring(ROLE_PREFIX.length()))
        .distinct()
        .sorted()
        .toList();
  }
}
