package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.operations.domain.Operation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/operations/{id}} (security.md §3): the requester and an ADMIN read it, another
 * USER gets 403 and an unknown ID 404.
 */
class OperationApiIT extends OperationsTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String REQUESTER = "user:" + TestJwt.USER_SUBJECT;

  @Test
  void theRequesterReadsItsOperation() {
    Operation operation = byHand(REQUESTER);

    ResponseEntity<String> response = get(LenientRestClient.forPort(port), operation.id());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode data = JSON.readTree(response.getBody()).path("data");
    assertThat(data.path("operationId").asString()).isEqualTo(operation.id().toString());
    assertThat(data.path("status").asString()).isEqualTo("QUEUED");
    assertThat(data.path("version").asInt()).isEqualTo(1);
    assertThat(data.path("statusTopic").asString())
        .isEqualTo("/topic/operations/" + operation.id());
  }

  @Test
  void aFailedOperationCarriesItsSafeErrorCodeAndMessage() {
    Operation operation = byHand(REQUESTER);
    Operation running = operations.markRunning(operation.id(), 1);
    operations.fail(operation.id(), running.version(), "OPERATION_FAILED", "The operation failed");

    ResponseEntity<String> response = get(LenientRestClient.forPort(port), operation.id());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode data = JSON.readTree(response.getBody()).path("data");
    assertThat(data.path("status").asString()).isEqualTo("FAILED");
    assertThat(data.path("version").asInt()).isEqualTo(3);
    assertThat(data.path("kind").asString()).isEqualTo("PROBE_BY_HAND");
    assertThat(data.path("errorCode").asString()).isEqualTo("OPERATION_FAILED");
    assertThat(data.path("errorMessage").asString()).isEqualTo("The operation failed");
    assertThat(data.path("createdAt").asString()).isNotBlank();
  }

  @Test
  void anAdminReadsAnyOperation() {
    Operation operation = byHand("user:someone-else");

    assertThat(get(LenientRestClient.admin(port), operation.id()).getStatusCode().value())
        .isEqualTo(200);
  }

  @Test
  void anotherUserIsForbidden() {
    Operation operation = byHand("user:someone-else");

    ResponseEntity<String> response = get(LenientRestClient.forPort(port), operation.id());

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(JSON.readTree(response.getBody()).path("errorCode").asString())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void anUnknownOperationIsNotFound() {
    ResponseEntity<String> response = get(LenientRestClient.forPort(port), UUID.randomUUID());

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(JSON.readTree(response.getBody()).path("errorCode").asString())
        .isEqualTo("OPERATION_NOT_FOUND");
  }

  @Test
  void anonymousCallersGet401() {
    assertThat(get(LenientRestClient.anonymous(port), UUID.randomUUID()).getStatusCode().value())
        .isEqualTo(401);
  }

  private Operation byHand(String actor) {
    return SystemActor.call(actor, () -> operations.start("PROBE_BY_HAND", null));
  }

  private static ResponseEntity<String> get(RestClient client, UUID id) {
    return client.get().uri("/api/v1/operations/" + id).retrieve().toEntity(String.class);
  }
}
