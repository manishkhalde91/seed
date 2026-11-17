package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.StompTestClient;
import com.company.fixtures.operations.StompTestClient.Frame;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.infrastructure.notify.PgStatusListener;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PgStatusListener} survives losing its connection (realtime-and-storage.md §1.2): when its
 * backend is terminated it logs a WARN, its health ({@code operationStatusListener}, part of
 * readiness) goes DOWN, it reconnects with backoff, health is UP again, it publishes a resync, and
 * later status changes are still pushed.
 */
@ExtendWith(OutputCaptureExtension.class)
class PgStatusListenerIT extends OperationsTestSupport {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  @Autowired private ApplicationContext context;
  @Autowired private JsonMapper json;

  @Autowired
  @Qualifier("operationStatusListenerHealthIndicator")
  private HealthIndicator health;

  @Test
  void aTerminatedConnectionIsReestablishedWithAResyncAndPushesContinue(CapturedOutput output)
      throws SQLException {
    Awaitility.await().atMost(WAIT).until(() -> health.health().getStatus().equals(Status.UP));
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.subscribe("resync", "/topic/system/resync");
      StompTestClient.awaitSubscribed(context, "/topic/system/resync");

      assertThat(terminateListenerBackends()).isPositive();

      Awaitility.await()
          .atMost(WAIT)
          .pollInterval(Duration.ofMillis(10))
          .until(() -> health.health().getStatus().equals(Status.DOWN));
      Awaitility.await().atMost(WAIT).until(() -> health.health().getStatus().equals(Status.UP));
      Frame resync = client.awaitMessage("/topic/system/resync");
      assertThat(json.readValue(resync.body(), JSON_OBJECT))
          .isEqualTo(Map.of("reason", "listener-reconnected"));
      assertThat(EcsLogLines.of(output.getAll(), PgStatusListener.class.getName()))
          .anySatisfy(
              line -> {
                assertThat(line.level()).isEqualTo("WARN");
                assertThat(line.message()).contains("dropped");
              });

      Operation started = startForTestUser("RECONNECT_PROBE", "after-reconnect");
      String topic = "/topic/operations/" + started.id();
      client.subscribe("after", topic);
      StompTestClient.awaitSubscribed(context, topic);
      operations.markRunning(started.id(), 1);

      assertThat(json.readValue(client.awaitMessage(topic).body(), JSON_OBJECT))
          .containsEntry("status", "RUNNING")
          .containsEntry("version", 2);
    }
  }

  @Test
  void anUnreadableNotificationIsSkippedAndTheConnectionCarriesOn(CapturedOutput output) {
    Awaitility.await().atMost(WAIT).until(() -> health.health().getStatus().equals(Status.UP));
    Operation started = startForTestUser("UNREADABLE_PROBE", "unreadable");
    String topic = "/topic/operations/" + started.id();
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.subscribe("resync", "/topic/system/resync");
      client.subscribe("status", topic);
      StompTestClient.awaitSubscribed(context, topic);

      jdbc.execute("SELECT pg_notify('operation_status', 'not json')");
      operations.markRunning(started.id(), 1);

      Frame push = client.next();
      assertThat(push.header("destination")).isEqualTo(topic);
      assertThat(json.readValue(push.body(), JSON_OBJECT)).containsEntry("version", 2);
      assertThat(client.poll(Duration.ofMillis(500))).as("no resync").isNull();
      assertThat(health.health().getStatus()).isEqualTo(Status.UP);
      assertThat(EcsLogLines.of(output.getAll(), PgStatusListener.class.getName()))
          .filteredOn(line -> line.message().contains("cannot be read"))
          .singleElement()
          .satisfies(
              line -> {
                assertThat(line.level()).isEqualTo("WARN");
                assertThat(line.message()).doesNotContain("not json");
              });
    }
  }

  /** Terminates the listener's backend, as a failover or an administrator would. */
  private static int terminateListenerBackends() throws SQLException {
    try (Connection superuser = TestDatabase.connectAsSuperuser("postgres");
        PreparedStatement terminate =
            superuser.prepareStatement(
                "SELECT count(*) FILTER (WHERE pg_terminate_backend(pid)) FROM pg_stat_activity"
                    + " WHERE application_name = ? AND datname = 'seed_operations'")) {
      terminate.setString(1, PgStatusListener.APPLICATION_NAME);
      try (ResultSet rows = terminate.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
