package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.StatusProbe;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may read the API docs (story 7): where they are enabled, the document and Swagger UI are
 * readable without a token (Swagger UI must load the document before Authorize), while every API
 * call still needs one; the document offers the issuer's sign-in. Where they are disabled (the
 * default profile) they are not found, token or not. The {@code local} profile has its own test
 * ({@code LocalDocsSecurityIT}), run in the local-profile JVM.
 */
class DocsSecurityIT {

  static final List<String> DOCS =
      List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("dev")
  @Import({TestDatabase.class, TestSecurity.class})
  class Dev {

    @LocalServerPort private int port;

    @Test
    void docsAreReadableWithoutATokenButApiCallsAreNot() {
      assertDocsOpenAndApiClosed(port);
    }

    @Test
    void theDocumentOffersBearerTokensAndTheIssuersSignIn() {
      assertSignInOffered(port);
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Import({TestDatabase.class, TestSecurity.class})
  class DefaultProfile {

    @LocalServerPort private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui/index.html"})
    void docsAreNotFoundWithOrWithoutAToken(String path) {
      assertThat(StatusProbe.anonymousStatus(port, path)).isEqualTo(404);
      assertThat(StatusProbe.status(port, path)).isEqualTo(404);
    }
  }

  static void assertDocsOpenAndApiClosed(int port) {
    for (String path : DOCS) {
      assertThat(StatusProbe.anonymousStatus(port, path)).as(path).isEqualTo(200);
    }
    String initializer =
        LenientRestClient.anonymous(port)
            .get()
            .uri("/swagger-ui/swagger-initializer.js")
            .retrieve()
            .body(String.class);
    assertThat(initializer).contains("seed-swagger").contains("usePkceWithAuthorizationCodeGrant");
    assertThat(StatusProbe.anonymousStatus(port, "/no/such/route")).isEqualTo(401);
    assertThat(StatusProbe.status(port, "/no/such/route")).isEqualTo(404);
  }

  static void assertSignInOffered(int port) {
    String body =
        LenientRestClient.anonymous(port).get().uri("/v3/api-docs").retrieve().body(String.class);
    JsonNode document = JSON.readTree(body);

    JsonNode schemes = document.path("components").path("securitySchemes");
    assertThat(schemes.path("bearer-jwt").path("scheme").asString()).isEqualTo("bearer");
    assertThat(schemes.path("oidc").path("type").asString()).isEqualTo("openIdConnect");
    assertThat(schemes.path("oidc").path("openIdConnectUrl").asString())
        .isEqualTo(TestJwt.ISSUER + "/.well-known/openid-configuration");
    List<String> requirements = new ArrayList<>();
    document
        .path("security")
        .forEach(requirement -> requirements.addAll(requirement.propertyNames()));
    assertThat(requirements).containsExactlyInAnyOrder("bearer-jwt", "oidc");
  }
}
