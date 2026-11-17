package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.SecurityProbeController;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.GlobalExceptionHandler;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.logging.RequestLoggingFilter;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The resource server against the running application (story 7 matrix): tokens, roles, method
 * security, CORS, security headers and the user on log lines. The roles-claim cases run in their
 * own contexts, with {@code OIDC_ROLES_CLAIM} changed.
 */
class SecurityIT {

  private static final String ALLOWED_ORIGIN = "http://localhost:4200";
  private static final Duration LOG_WAIT = Duration.ofSeconds(5);

  /** A path whose exception escapes the chain after authentication (an ERROR dispatch). */
  static final String ESCAPE = SecurityProbeController.BASE + "/escape";

  @TestConfiguration(proxyBeanMethods = false)
  static class EscapingFilter {

    /** Runs after the security chain and throws for {@link #ESCAPE}. */
    @Bean
    FilterRegistrationBean<Filter> escapingFilter() {
      FilterRegistrationBean<Filter> registration =
          new FilterRegistrationBean<>(
              (request, response, chain) -> {
                throw new IllegalStateException("security probe filter failure");
              });
      registration.addUrlPatterns(ESCAPE);
      registration.setOrder(-50);
      return registration;
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Import({
    TestDatabase.class,
    TestSecurity.class,
    SecurityProbeController.class,
    SecurityIT.EscapingFilter.class
  })
  @ExtendWith(OutputCaptureExtension.class)
  class Defaults {

    @LocalServerPort private int port;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void noTokenIsAn401EnvelopeWithTheBearerChallengeAndTheCorrelationId(CapturedOutput output) {
      for (String path : List.of(SecurityProbeController.USER_ONLY, "/no/such/route")) {
        String id = "anon-" + UUID.randomUUID();

        ResponseEntity<String> response = get(LenientRestClient.anonymous(port), path, id);

        assertUnauthenticated(response, id);
        assertThat(lines(output, id))
            .anySatisfy(
                line -> {
                  assertThat(line.logger()).isEqualTo(JsonAuthenticationEntryPoint.class.getName());
                  assertThat(line.level()).isEqualTo("INFO");
                });
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.company.seed.security.SecurityIT#badTokens")
    void aTokenFailingAnyCheckIsA401ThatNamesNoReason(
        String description, String token, CapturedOutput output) {
      String id = "bad-" + UUID.randomUUID();

      ResponseEntity<String> response =
          get(LenientRestClient.forPort(port, token), SecurityProbeController.USER_ONLY, id);

      assertUnauthenticated(response, id);
      assertThat(response.getBody())
          .doesNotContain(token)
          .doesNotContainIgnoringCase("expired")
          .doesNotContainIgnoringCase("audience")
          .doesNotContainIgnoringCase("issuer")
          .doesNotContainIgnoringCase("signature")
          .doesNotContainIgnoringCase("jwt");
      awaitSummary(output, id);
      assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void aTokenWithinTheClockSkewIsAccepted() {
      Instant now = Instant.now();
      String expired20sAgo =
          TestJwt.builder().roles(Roles.USER).expiresAt(now.minusSeconds(20)).sign();
      String valid20sFromNow =
          TestJwt.builder().roles(Roles.USER).notBefore(now.plusSeconds(20)).sign();

      for (String token : List.of(expired20sAgo, valid20sFromNow)) {
        assertThat(status(token, SecurityProbeController.USER_ONLY)).isEqualTo(200);
      }
    }

    @ParameterizedTest(name = "{0} -> user {1}, admin {2}")
    @CsvSource({"USER, 200, 403", "ADMIN, 200, 200", "'', 403, 403"})
    void rolesDecideMethodSecurityAndAdminImpliesUser(
        String role, int userOnly, int adminOnly, CapturedOutput output) {
      TestJwt.Builder builder = TestJwt.builder();
      String token = role.isEmpty() ? builder.sign() : builder.roles(role).sign();

      assertThat(status(token, SecurityProbeController.USER_ONLY)).isEqualTo(userOnly);
      String id = "role-" + UUID.randomUUID();
      ResponseEntity<String> admin =
          get(LenientRestClient.forPort(port, token), SecurityProbeController.ADMIN_ONLY, id);
      assertThat(admin.getStatusCode().value()).isEqualTo(adminOnly);
      if (adminOnly == 403) {
        assertForbidden(admin, id);
        assertThat(lines(output, id))
            .anySatisfy(
                line -> {
                  assertThat(line.logger()).isEqualTo(GlobalExceptionHandler.class.getName());
                  assertThat(line.level()).isEqualTo("WARN");
                  assertThat(line.message()).contains("subject=" + TestJwt.USER_SUBJECT);
                });
      }
    }

    @Test
    void lowerCaseRolesAreUpperCased() {
      assertThat(
              status(TestJwt.builder().roles("admin").sign(), SecurityProbeController.ADMIN_ONLY))
          .isEqualTo(200);
    }

    @Test
    void preflightFromAnAllowedOriginIsAnsweredWithTheCorsRules() {
      ResponseEntity<String> response = preflight(ALLOWED_ORIGIN);

      assertThat(response.getStatusCode().value()).isEqualTo(200);
      HttpHeaders headers = response.getHeaders();
      assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
      assertThat(headers.getAccessControlAllowMethods())
          .map(HttpMethod::name)
          .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
      assertThat(headers.getAccessControlAllowHeaders())
          .map(String::toLowerCase)
          .contains("authorization", "content-type", "x-correlation-id", "idempotency-key");
      assertThat(headers.getAccessControlMaxAge()).isEqualTo(3600);
    }

    @Test
    void preflightFromAnotherOriginIsRefused() {
      ResponseEntity<String> response = preflight("https://evil.example");

      assertThat(response.getStatusCode().value()).isEqualTo(403);
      assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
      assertThat(parse(response.getBody()))
          .containsEntry("success", false)
          .containsEntry("errorCode", ErrorCode.FORBIDDEN)
          .containsEntry("message", "Access denied")
          .containsEntry(
              "correlationId", response.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "async, " + SecurityProbeController.ASYNC + ", 200",
      "error dispatch, " + ESCAPE + ", 500"
    })
    void theSummaryLineOfAnAsyncOrFailedRequestCarriesTheSubject(
        String description, String path, int status, CapturedOutput output) {
      String id = "redispatch-" + UUID.randomUUID();
      String token = TestJwt.builder().subject("u-3").roles(Roles.USER).sign();

      ResponseEntity<String> response = get(LenientRestClient.forPort(port, token), path, id);

      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(awaitSummary(output, id).field("user.id")).isEqualTo("u-3");
    }

    @Test
    void anAnonymousRequestAnsweredByAnErrorDispatchKeepsItsOwnStatus() {
      String id = "firewall-" + UUID.randomUUID();

      // The firewall rejects the semicolon before authentication; /error answers the rejection.
      ResponseEntity<String> response =
          get(LenientRestClient.anonymous(port), SecurityProbeController.USER_ONLY + ";x=1", id);

      assertThat(response.getStatusCode().value()).isEqualTo(400);
      assertThat(parse(response.getBody()))
          .containsEntry("status", 400)
          .containsEntry("errorCode", ErrorCode.MALFORMED_REQUEST)
          .containsEntry("correlationId", id);
    }

    @Test
    void aCrossOriginCallExposesTheApiHeaders() {
      ResponseEntity<String> response =
          LenientRestClient.forPort(port)
              .get()
              .uri(SecurityProbeController.USER_ONLY)
              .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
              .retrieve()
              .toEntity(String.class);

      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
      assertThat(response.getHeaders().getAccessControlExposeHeaders())
          .map(String::toLowerCase)
          .contains("x-correlation-id", "location", "idempotent-replayed", "retry-after");
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() {
      List<ResponseEntity<String>> responses =
          List.of(
              get(LenientRestClient.forPort(port), SecurityProbeController.USER_ONLY, null),
              get(LenientRestClient.anonymous(port), SecurityProbeController.USER_ONLY, null),
              get(LenientRestClient.forPort(port), SecurityProbeController.ADMIN_ONLY, null),
              get(LenientRestClient.forPort(port), "/no/such/route", null));

      assertThat(responses)
          .extracting(response -> response.getStatusCode().value())
          .containsExactly(200, 401, 403, 404);
      assertThat(responses).allSatisfy(SecurityIT::assertSecurityHeaders);
    }

    @Test
    void anAuthenticatedRequestLogsTheSubjectAndAScopeItsName(CapturedOutput output) {
      String id = "log-" + UUID.randomUUID();
      String token = TestJwt.builder().subject("u-1").roles(Roles.USER).sign();

      ResponseEntity<String> response =
          get(LenientRestClient.forPort(port, token), SecurityProbeController.ACTOR, id);

      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(parse(response.getBody())).containsEntry("data", "user:u-1");
      EcsLogLines.Line summary = awaitSummary(output, id);
      assertThat(summary.field("user.id")).isEqualTo("u-1");
      List<EcsLogLines.Line> lines = lines(output, id);
      assertThat(line(lines, SecurityProbeController.HANDLER_LINE).field("user.id"))
          .isEqualTo("u-1");
      assertThat(line(lines, SecurityProbeController.SYSTEM_LINE).field("user.id"))
          .isEqualTo(SecurityProbeController.SYSTEM_SCOPE);
      assertThat(line(lines, SecurityProbeController.AFTER_SCOPE_LINE).field("user.id"))
          .isEqualTo("u-1");
      assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void anAnonymousOrRejectedRequestLogsNoUser(CapturedOutput output) {
      String anonymous = "nouser-" + UUID.randomUUID();
      String rejected = "rejected-" + UUID.randomUUID();
      String badToken = TestJwt.builder().subject("u-2").audience("other-api").sign();

      get(LenientRestClient.anonymous(port), SecurityProbeController.ACTOR, anonymous);
      get(LenientRestClient.forPort(port, badToken), SecurityProbeController.ACTOR, rejected);

      for (String id : List.of(anonymous, rejected)) {
        awaitSummary(output, id);
        assertThat(lines(output, id))
            .isNotEmpty()
            .allSatisfy(line -> assertThat(line.field("user.id")).isNull());
      }
      assertThat(output.getAll()).doesNotContain(badToken);
    }

    private ResponseEntity<String> preflight(String origin) {
      return LenientRestClient.anonymous(port)
          .options()
          .uri(SecurityProbeController.USER_ONLY)
          .header(HttpHeaders.ORIGIN, origin)
          .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
          .header(
              HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
              "Authorization, Content-Type, X-Correlation-Id, Idempotency-Key")
          .retrieve()
          .toEntity(String.class);
    }

    private int status(String token, String path) {
      return get(LenientRestClient.forPort(port, token), path, null).getStatusCode().value();
    }

    private void assertUnauthenticated(ResponseEntity<String> response, String id) {
      assertThat(response.getStatusCode().value()).isEqualTo(401);
      assertThat(response.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
      assertThat(parse(response.getBody()))
          .containsEntry("success", false)
          .containsEntry("status", 401)
          .containsEntry("errorCode", ErrorCode.UNAUTHENTICATED)
          .containsEntry("message", "Authentication required")
          .containsEntry("correlationId", id);
    }

    private void assertForbidden(ResponseEntity<String> response, String id) {
      assertThat(parse(response.getBody()))
          .containsEntry("success", false)
          .containsEntry("status", 403)
          .containsEntry("errorCode", ErrorCode.FORBIDDEN)
          .containsEntry("correlationId", id);
    }

    private Map<String, Object> parse(String json) {
      assertThat(json).isNotBlank();
      return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
  }

  /** {@code OIDC_ROLES_CLAIM} names another top-level claim. */
  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "OIDC_ROLES_CLAIM=groups")
  @Import({TestDatabase.class, TestSecurity.class, SecurityProbeController.class})
  class CustomRolesClaim {

    @LocalServerPort private int port;

    @Test
    void rolesAreReadFromTheConfiguredClaimOnly() {
      String groups = TestJwt.builder().claim("groups", List.of(Roles.ADMIN)).sign();
      String roles = TestJwt.builder().roles(Roles.ADMIN).sign();

      assertThat(statusOf(port, groups, SecurityProbeController.ADMIN_ONLY)).isEqualTo(200);
      assertThat(statusOf(port, roles, SecurityProbeController.ADMIN_ONLY)).isEqualTo(403);
    }
  }

  /** {@code OIDC_ROLES_CLAIM} is a dotted path into a nested object, as Keycloak issues them. */
  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "OIDC_ROLES_CLAIM=realm_access.roles")
  @Import({TestDatabase.class, TestSecurity.class, SecurityProbeController.class})
  class NestedRolesClaim {

    @LocalServerPort private int port;

    @Test
    void rolesAreReadFromTheNestedPathAndUpperCased() {
      String token =
          TestJwt.builder().claim("realm_access", Map.of("roles", List.of("admin"))).sign();

      assertThat(statusOf(port, token, SecurityProbeController.ADMIN_ONLY)).isEqualTo(200);
      assertThat(statusOf(port, token, SecurityProbeController.USER_ONLY)).isEqualTo(200);
    }
  }

  static Stream<Arguments> badTokens() {
    Instant now = Instant.now();
    return Stream.of(
        Arguments.of(
            "expired beyond the skew",
            TestJwt.builder().roles(Roles.USER).expiresAt(now.minusSeconds(60)).sign()),
        Arguments.of(
            "not yet valid beyond the skew",
            TestJwt.builder().roles(Roles.USER).notBefore(now.plusSeconds(120)).sign()),
        Arguments.of(
            "wrong issuer",
            TestJwt.builder().roles(Roles.USER).issuer("https://other.example/seed").sign()),
        Arguments.of(
            "wrong audience", TestJwt.builder().roles(Roles.USER).audience("other-api").sign()),
        Arguments.of(
            "bad signature", TestJwt.builder().roles(Roles.USER).signedWithForeignKey().sign()),
        Arguments.of("alg none", TestJwt.builder().roles(Roles.USER).unsigned()),
        Arguments.of("not a JWT", "not-a-jwt"),
        Arguments.of("no exp", TestJwt.builder().roles(Roles.USER).claim("exp", null).sign()),
        Arguments.of("no sub", TestJwt.builder().roles(Roles.USER).subject(null).sign()),
        Arguments.of("blank sub", TestJwt.builder().roles(Roles.USER).subject("  ").sign()),
        Arguments.of(
            "sub with a newline", TestJwt.builder().roles(Roles.USER).subject("u\n1").sign()),
        Arguments.of(
            "sub of 256 characters",
            TestJwt.builder().roles(Roles.USER).subject("u".repeat(256)).sign()));
  }

  private static int statusOf(int port, String token, String path) {
    return LenientRestClient.forPort(port, token)
        .get()
        .uri(path)
        .retrieve()
        .toBodilessEntity()
        .getStatusCode()
        .value();
  }

  private static ResponseEntity<String> get(RestClient client, String path, String correlationId) {
    RestClient.RequestHeadersSpec<?> request = client.get().uri(path);
    if (correlationId != null) {
      request.header(CorrelationIdFilter.HEADER, correlationId);
    }
    return request.retrieve().toEntity(String.class);
  }

  static void assertSecurityHeaders(ResponseEntity<?> response) {
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
  }

  /** The captured log lines carrying {@code correlationId}. */
  private static List<EcsLogLines.Line> lines(CapturedOutput output, String correlationId) {
    return EcsLogLines.all(output.getOut()).stream()
        .filter(line -> correlationId.equals(line.field("correlationId")))
        .toList();
  }

  private static EcsLogLines.Line line(List<EcsLogLines.Line> lines, String message) {
    return lines.stream()
        .filter(line -> line.message().equals(message))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No log line " + message + " in " + lines));
  }

  private static EcsLogLines.Line awaitSummary(CapturedOutput output, String correlationId) {
    return await()
        .atMost(LOG_WAIT)
        .until(
            () ->
                lines(output, correlationId).stream()
                    .filter(line -> line.logger().equals(RequestLoggingFilter.class.getName()))
                    .toList(),
            found -> !found.isEmpty())
        .getFirst();
  }
}
