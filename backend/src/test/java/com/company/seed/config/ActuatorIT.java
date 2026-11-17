package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Health probes, metrics and loggers on the management port; nothing else of Actuator is exposed,
 * and nothing of it on the application port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class})
class ActuatorIT {

  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;
  @Autowired private JsonMapper jsonMapper;

  private RestClient app;
  private RestClient management;

  @BeforeEach
  void createClients() {
    app = LenientRestClient.forPort(port);
    management = LenientRestClient.anonymous(managementPort);
  }

  @Test
  void managementRunsOnItsOwnPort() {
    assertThat(managementPort).isNotEqualTo(port);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void anonymousProbeIsUpWithoutComponentsOrDetails(String path) {
    ResponseEntity<String> response = management.get().uri(path).retrieve().toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(parse(response.getBody())).isEqualTo(Map.of("status", "UP"));
  }

  @Test
  void anonymousHealthShowsNoComponents() {
    ResponseEntity<String> response =
        management.get().uri("/actuator/health").retrieve().toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(parse(response.getBody())).containsOnlyKeys("status", "groups");
  }

  @Test
  void prometheusExposesHttpAndJvmMetrics() {
    app.get().uri("/no/such/route").retrieve().toBodilessEntity();

    String metrics =
        await()
            .atMost(Duration.ofSeconds(5))
            .until(
                () -> management.get().uri("/actuator/prometheus").retrieve().body(String.class),
                body -> body != null && body.contains("http_server_requests_seconds"));
    assertThat(metrics).contains("jvm_");
  }

  @Test
  void prometheusExposesTheConnectionPoolMetrics() {
    String metrics = management.get().uri("/actuator/prometheus").retrieve().body(String.class);

    assertThat(metrics).contains("hikaricp_connections");
  }

  /** Asked with an ADMIN token, so only exposure decides (ManagementSecurityIT covers access). */
  @ParameterizedTest
  @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/metrics"})
  void otherEndpointsAreNotExposed(String path) {
    assertThat(
            LenientRestClient.admin(managementPort)
                .get()
                .uri(path)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/actuator"})
  void actuatorIsNotServedOnTheApplicationPort(String path) {
    ResponseEntity<String> response = app.get().uri(path).retrieve().toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(parse(response.getBody()))
        .containsEntry("errorCode", "NOT_FOUND")
        .containsEntry("success", false);
  }

  private Map<String, Object> parse(String json) {
    assertThat(json).isNotBlank();
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }
}
