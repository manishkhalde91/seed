package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny by default (security.md §2): every handler method of {@code com.company.seed}, as Spring MVC
 * maps it, answers 401 to a request without a token, 403 to a token without roles when its {@code
 * PreAuthorize} needs a role, and neither to a valid {@code ADMIN} token. A new endpoint is covered
 * without listing it.
 *
 * <p>Path variables get a UUID and a handler that reads a body gets an empty JSON object, which
 * fails validation: the role check runs before binding ({@code PreAuthorizeHandlerInterceptor}), so
 * a role-less caller still gets 403, never a 400 describing the body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class})
class RouteTableIT {

  /** A {@code @PreAuthorize} expression that needs a role or authority. */
  private static final Pattern ROLE_CHECK = Pattern.compile("has(Any)?(Role|Authority)\\(");

  @LocalServerPort private int port;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  /** Stands in for every path variable: a valid UUID (and so a valid string). */
  private static final String PATH_VARIABLE = "00000000-0000-7000-8000-000000000001";

  private record Route(
      HttpMethod method, String path, String handler, boolean needsRole, boolean hasBody) {}

  @Test
  void everySeedHandlerMethodRejectsARequestWithoutAToken() {
    List<Route> routes = routes();

    assertThat(routes).as("seed handler methods").isNotEmpty();
    for (Route route : routes) {
      assertThat(status(LenientRestClient.anonymous(port), route))
          .as(route.toString())
          .isEqualTo(401);
    }
  }

  /** A token without roles reaches only the handlers whose {@code @PreAuthorize} needs none. */
  @Test
  void aRoleLessTokenIsForbiddenWhereTheHandlerNeedsARole() {
    RestClient roleLess = LenientRestClient.forPort(port, TestJwt.builder().sign());

    for (Route route : routes()) {
      int status = status(roleLess, route);
      if (route.needsRole()) {
        assertThat(status).as(route.toString()).isEqualTo(403);
      } else {
        assertThat(status).as(route.toString()).isNotIn(401, 403);
      }
    }
  }

  @Test
  void aValidTokenIsNeitherUnauthenticatedNorForbidden() {
    RestClient admin = LenientRestClient.admin(port);

    for (Route route : routes()) {
      assertThat(status(admin, route)).as(route.toString()).isNotIn(401, 403);
    }
  }

  private static int status(RestClient client, Route route) {
    RestClient.RequestBodySpec request = client.method(route.method()).uri(route.path());
    if (route.hasBody()) {
      request.contentType(MediaType.APPLICATION_JSON).body("{}");
    }
    return request.retrieve().toBodilessEntity().getStatusCode().value();
  }

  private List<Route> routes() {
    List<Route> routes = new ArrayList<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              if (!handler.getBeanType().getPackageName().startsWith("com.company.seed")) {
                return;
              }
              Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
              List<HttpMethod> httpMethods =
                  methods.isEmpty()
                      ? List.of(HttpMethod.GET, HttpMethod.POST)
                      : methods.stream().map(RequestMethod::asHttpMethod).toList();
              PreAuthorize rule =
                  AnnotatedElementUtils.findMergedAnnotation(
                      handler.getMethod(), PreAuthorize.class);
              boolean needsRole = rule != null && ROLE_CHECK.matcher(rule.value()).find();
              boolean hasBody =
                  Arrays.stream(handler.getMethodParameters())
                      .anyMatch(parameter -> parameter.hasParameterAnnotation(RequestBody.class));
              for (String pattern : mapping.getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]*}", PATH_VARIABLE);
                httpMethods.forEach(
                    method ->
                        routes.add(
                            new Route(method, path, handler.toString(), needsRole, hasBody)));
              }
            });
    return routes;
  }
}
