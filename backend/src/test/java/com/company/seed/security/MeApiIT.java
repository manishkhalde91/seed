package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/me}: the caller's subject, display name and effective roles, in the envelope,
 * for any authenticated caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class})
class MeApiIT {

  private static final String ME = "/api/v1/me";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @Test
  void aUserSeesTheirSubjectNameAndRole() {
    String token = TestJwt.builder().subject("u-1").claim("name", "Ann").roles(Roles.USER).sign();

    JsonNode body = get(LenientRestClient.forPort(port, token), 200);

    assertThat(body.get("message").asString()).isEqualTo("Current user");
    assertThat(body.get("success").asBoolean()).isTrue();
    JsonNode data = body.get("data");
    assertThat(data.get("subject").asString()).isEqualTo("u-1");
    assertThat(data.get("name").asString()).isEqualTo("Ann");
    assertThat(data.get("roles").toString()).isEqualTo("[\"USER\"]");
  }

  @Test
  void anAdminWithoutANameGetsThePreferredUsernameAndTheImpliedUserRole() {
    String token =
        TestJwt.builder()
            .subject("u-2")
            .claim("preferred_username", "root")
            .roles(Roles.ADMIN)
            .sign();

    JsonNode data = get(LenientRestClient.forPort(port, token), 200).get("data");

    assertThat(data.get("subject").asString()).isEqualTo("u-2");
    assertThat(data.get("name").asString()).isEqualTo("root");
    assertThat(data.get("roles").toString()).isEqualTo("[\"ADMIN\",\"USER\"]");
  }

  @Test
  void withoutANameClaimTheSubjectIsTheNameAndACallerWithoutRolesIsAllowed() {
    String token = TestJwt.builder().subject("u-3").sign();

    JsonNode data = get(LenientRestClient.forPort(port, token), 200).get("data");

    assertThat(data.get("name").asString()).isEqualTo("u-3");
    assertThat(data.get("roles").toString()).isEqualTo("[]");
  }

  @Test
  void withoutATokenItIs401() {
    JsonNode body = get(LenientRestClient.anonymous(port), 401);

    assertThat(body.get("errorCode").asString()).isEqualTo("UNAUTHENTICATED");
  }

  private static JsonNode get(RestClient client, int expectedStatus) {
    ResponseEntity<String> response = client.get().uri(ME).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
    return JSON.readTree(response.getBody());
  }
}
