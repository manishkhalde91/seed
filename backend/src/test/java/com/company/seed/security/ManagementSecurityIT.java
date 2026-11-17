package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.logging.CorrelationIdFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access to the management port (security.md §3, story 7): probes and Prometheus are open, the
 * loggers endpoint needs {@code ADMIN}, and only {@code ADMIN} sees health components.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class})
class ManagementSecurityIT {

  private static final String LOGGERS = "/actuator/loggers";

  @LocalManagementPort private int managementPort;
  @Autowired private JsonMapper jsonMapper;

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus"})
  void probesAndMetricsAreOpen(String path) {
    assertThat(status(LenientRestClient.anonymous(managementPort), path)).isEqualTo(200);
  }

  @Test
  void loggersNeedsAnAdminToken() {
    ResponseEntity<String> anonymous =
        LenientRestClient.anonymous(managementPort)
            .get()
            .uri(LOGGERS)
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> user =
        LenientRestClient.forPort(managementPort)
            .get()
            .uri(LOGGERS)
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> admin =
        LenientRestClient.admin(managementPort)
            .get()
            .uri(LOGGERS)
            .retrieve()
            .toEntity(String.class);

    assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    assertThat(anonymous.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
    assertThat(parse(anonymous.getBody()))
        .containsEntry("errorCode", ErrorCode.UNAUTHENTICATED)
        .containsEntry(
            "correlationId", anonymous.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    assertThat(user.getStatusCode().value()).isEqualTo(403);
    assertThat(parse(user.getBody()))
        .containsEntry("errorCode", ErrorCode.FORBIDDEN)
        .containsEntry("correlationId", user.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    assertThat(anonymous.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotBlank();
    assertThat(admin.getStatusCode().value()).isEqualTo(200);
    assertThat(parse(admin.getBody())).containsKey("loggers");
  }

  @Test
  void onlyAdminSeesHealthComponents() {
    Map<String, Object> anonymous = health(LenientRestClient.anonymous(managementPort));
    Map<String, Object> user = health(LenientRestClient.forPort(managementPort));
    Map<String, Object> admin = health(LenientRestClient.admin(managementPort));

    assertThat(anonymous).doesNotContainKey("components");
    assertThat(user).doesNotContainKey("components");
    assertThat(admin).containsKey("components");
    assertThat(((Map<?, ?>) admin.get("components")).keySet())
        .map(Object::toString)
        .contains("db", "schemaVersion");
  }

  @Test
  void anUnexposedEndpointIsNotFoundForAnAdmin() {
    assertThat(status(LenientRestClient.admin(managementPort), "/actuator/env")).isEqualTo(404);
  }

  private Map<String, Object> health(RestClient client) {
    ResponseEntity<String> response =
        client.get().uri("/actuator/health").retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return parse(response.getBody());
  }

  private static int status(RestClient client, String path) {
    return client.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
  }

  private Map<String, Object> parse(String json) {
    assertThat(json).isNotBlank();
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }
}
