package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.SecondInstance;
import com.company.fixtures.operations.StompTestClient;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.operations.domain.Operation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-instance fan-out (realtime-and-storage.md §1.3): two instances on one database, no sticky
 * sessions. A client connected to instance B receives the status changes made through instance A,
 * because B's {@code PgStatusListener} hears A's {@code pg_notify}.
 */
class OperationStatusFanOutStompIT extends OperationsTestSupport {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void aClientOnInstanceBReceivesAChangeMadeThroughInstanceA() {
    Operation started = startForTestUser("FAN_OUT_PROBE", "fan-out-" + UUID.randomUUID());
    String topic = "/topic/operations/" + started.id();
    ConfigurableApplicationContext instanceB = SecondInstance.start();
    try (StompTestClient client =
        StompTestClient.connect(SecondInstance.port(instanceB), TestJwt.user())) {
      client.subscribe("fan-out", topic);
      StompTestClient.awaitSubscribed(instanceB, topic);

      operations.markRunning(started.id(), 1);

      assertThat(JSON.readValue(client.awaitMessage(topic).body(), JSON_OBJECT))
          .containsEntry("operationId", started.id().toString())
          .containsEntry("status", "RUNNING")
          .containsEntry("version", 2)
          .containsEntry("correlationId", started.correlationId());
    } finally {
      instanceB.close();
    }
  }
}
