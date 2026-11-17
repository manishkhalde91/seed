package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.SecurityProbeController;
import com.company.fixtures.security.StubIssuer;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.logging.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production {@code JwtDecoder} (no {@code TestSecurity}): discovery and JWKS fetched from the
 * issuer on the first token, then the production validator. An issuer that cannot be reached is a
 * 503, not a 401 or a 500.
 */
class IssuerIT {

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Import({TestDatabase.class, SecurityProbeController.class})
  class ReachableIssuer {

    private static final StubIssuer ISSUER = StubIssuer.start();

    @AfterAll
    static void stopIssuer() {
      ISSUER.close();
    }

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
      registry.add("OIDC_ISSUER_URI", ISSUER::issuer);
    }

    @LocalServerPort private int port;

    @Test
    void aValidTokenSignedWithTheIssuersKeyIsAccepted() {
      String token = TestJwt.builder().issuer(ISSUER.issuer()).roles(Roles.USER).sign();

      assertThat(status(port, token)).isEqualTo(200);
    }

    @Test
    void theProductionValidatorRejectsAWrongAudienceAndAMissingExpiry() {
      String wrongAudience =
          TestJwt.builder().issuer(ISSUER.issuer()).roles(Roles.USER).audience("other-api").sign();
      String noExpiry =
          TestJwt.builder().issuer(ISSUER.issuer()).roles(Roles.USER).claim("exp", null).sign();

      assertThat(status(port, wrongAudience)).isEqualTo(401);
      assertThat(status(port, noExpiry)).isEqualTo(401);
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Import({TestDatabase.class, SecurityProbeController.class})
  @ExtendWith(OutputCaptureExtension.class)
  class UnreachableIssuer {

    // Port 1 on loopback: nothing listens there (binding it needs root), so every connection is
    // refused. A port picked free and released could be taken by a later test server.
    private static final String ISSUER = "http://127.0.0.1:1/seed";

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
      registry.add("OIDC_ISSUER_URI", () -> ISSUER);
    }

    @LocalServerPort private int port;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void aTokenThatCannotBeCheckedIsServiceUnavailable(CapturedOutput output) {
      String id = "issuer-down-" + UUID.randomUUID();
      String token = TestJwt.builder().issuer(ISSUER).roles(Roles.USER).sign();

      ResponseEntity<String> response =
          LenientRestClient.forPort(port, token)
              .get()
              .uri(SecurityProbeController.USER_ONLY)
              .header(CorrelationIdFilter.HEADER, id)
              .retrieve()
              .toEntity(String.class);

      assertThat(response.getStatusCode().value()).isEqualTo(503);
      Map<String, Object> body =
          jsonMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
      assertThat(body)
          .containsEntry("errorCode", ErrorCode.SERVICE_UNAVAILABLE)
          .containsEntry("correlationId", id);
      assertThat(response.getBody()).doesNotContain(token);
      assertThat(output.getAll())
          .contains(JsonAuthenticationEntryPoint.class.getName())
          .doesNotContain(token);
    }
  }

  private static int status(int port, String token) {
    return LenientRestClient.forPort(port, token)
        .get()
        .uri(SecurityProbeController.USER_ONLY)
        .retrieve()
        .toBodilessEntity()
        .getStatusCode()
        .value();
  }
}
