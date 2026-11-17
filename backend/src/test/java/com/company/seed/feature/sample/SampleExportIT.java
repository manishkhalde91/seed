package com.company.seed.feature.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.web.LenientRestClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DEMO ONLY. The sample export end to end (realtime-and-storage.md §1.2): 202 with the operation,
 * the async listener's work with the request's context, and the finished operation over REST.
 */
@ExtendWith(OutputCaptureExtension.class)
class SampleExportIT extends SampleTestSupport {

  private static final String BASE = "/api/v1/sample-records";
  private static final String EXPORT = BASE + "/export";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;

  @Test
  void anExportStartsQueuedAndAnswers202WithWhereToFollowIt() {
    ResponseEntity<String> response = export(LenientRestClient.forPort(port), "start-1");

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    JsonNode body = JSON.readTree(response.getBody());
    String operationId = body.path("data").path("operationId").asString();
    assertThat(body.path("message").asString()).isEqualTo("Export started");
    assertThat(response.getHeaders().getLocation())
        .hasToString("/api/v1/operations/" + operationId);
    assertThat(body.path("data").path("status").asString()).isEqualTo("QUEUED");
    assertThat(body.path("data").path("version").asInt()).isEqualTo(1);
    assertThat(body.path("data").path("statusTopic").asString())
        .isEqualTo("/topic/operations/" + operationId);
  }

  @Test
  void callersWithoutATokenOrTheUserRoleAreRejected() {
    assertThat(export(LenientRestClient.anonymous(port), "anon").getStatusCode().value())
        .isEqualTo(401);
    String roleless = TestJwt.builder().subject("no-roles").sign();
    assertThat(
            export(LenientRestClient.forPort(port, roleless), "roleless").getStatusCode().value())
        .isEqualTo(403);
  }

  @Test
  void anExportCountsTheActiveRecordsAndCarriesTheRequestContext(CapturedOutput output) {
    RestClient user = LenientRestClient.forPort(port);
    create(user, "Alpha");
    create(user, "Beta, with comma");
    create(user, "Gamma");
    UUID deleted = create(user, "Deleted");
    LenientRestClient.admin(port).delete().uri(BASE + "/" + deleted).retrieve().toBodilessEntity();

    String operationId =
        JSON.readTree(export(user, "export-ctx").getBody())
            .path("data")
            .path("operationId")
            .asString();

    JsonNode done =
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .until(
                () -> operation(user, operationId),
                found -> found.path("status").asString().equals("SUCCEEDED"));
    assertThat(done.path("version").asInt()).isEqualTo(3);
    assertThat(JSON.convertValue(done.path("result"), new TypeReference<Map<String, Object>>() {}))
        .isEqualTo(Map.of("rowCount", 3));
    assertThat(done.path("correlationId").asString()).isEqualTo("export-ctx");
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_by FROM common.operation WHERE id = ?::uuid",
                String.class,
                operationId))
        .isEqualTo("user:" + TestJwt.USER_SUBJECT);
    assertThat(
            EcsLogLines.of(
                    output.getOut(),
                    "com.company.seed.feature.sample.application.SampleExportListener")
                .stream()
                .filter(line -> line.message().startsWith("Sample export built")))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.field("correlationId")).isEqualTo("export-ctx");
              assertThat(line.field("user.id")).isEqualTo(TestJwt.USER_SUBJECT);
              assertThat(line.field("operationId")).isEqualTo(operationId);
            });
    String metrics =
        LenientRestClient.anonymous(managementPort)
            .get()
            .uri("/actuator/prometheus")
            .retrieve()
            .body(String.class);
    assertThat(metrics)
        .containsPattern(
            "seed_operation_total\\{[^}]*kind=\"SAMPLE_EXPORT\"[^}]*status=\"SUCCEEDED\"");
  }

  private ResponseEntity<String> export(RestClient client, String correlationId) {
    return client
        .post()
        .uri(EXPORT)
        .header("X-Correlation-Id", correlationId)
        .retrieve()
        .toEntity(String.class);
  }

  private static UUID create(RestClient client, String name) {
    String body =
        client
            .post()
            .uri(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"name\":\"" + name + "\"}")
            .retrieve()
            .body(String.class);
    return UUID.fromString(JSON.readTree(body).path("data").path("id").asString());
  }

  private static JsonNode operation(RestClient client, String operationId) {
    return JSON.readTree(
            client.get().uri("/api/v1/operations/" + operationId).retrieve().body(String.class))
        .path("data");
  }
}
