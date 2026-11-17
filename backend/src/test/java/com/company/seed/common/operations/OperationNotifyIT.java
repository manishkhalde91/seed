package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.operations.domain.InvalidOperationTransitionException;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Status changes through {@code OperationService} (realtime-and-storage.md §1.2): each is one
 * conditional update plus a {@code pg_notify} on {@code operation_status}, read here through a raw
 * LISTEN connection; a lost conditional update changes nothing and notifies nothing.
 */
class OperationNotifyIT extends OperationsTestSupport {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  @Autowired private JsonMapper json;

  /** Starts a notify probe as a user, the actor its work would run as. */
  private Operation start() {
    return SystemActor.call("user:notify-it", () -> operations.start("NOTIFY_PROBE", null));
  }

  @Test
  void runningAndSucceededAreNotifiedInVersionOrderAsThePushPayload() throws Exception {
    try (Connection listen = listen()) {
      Operation started = start();
      Operation running = operations.markRunning(started.id(), 1);
      Operation succeeded = operations.complete(started.id(), running.version(), Map.of("n", 1));

      List<Map<String, Object>> pushes = drain(listen, started.id().toString(), 2);

      assertThat(running.version()).isEqualTo(2);
      assertThat(succeeded.version()).isEqualTo(3);
      assertThat(pushes)
          .extracting(push -> push.get("status"))
          .containsExactly("RUNNING", "SUCCEEDED");
      assertThat(pushes).extracting(push -> push.get("version")).containsExactly(2, 3);
      assertThat(pushes.get(1))
          .containsOnlyKeys(
              "operationId",
              "kind",
              "subjectId",
              "status",
              "version",
              "result",
              "errorCode",
              "errorMessage",
              "updatedAt",
              "correlationId")
          .containsEntry("kind", "NOTIFY_PROBE")
          .containsEntry("result", Map.of("n", 1))
          .containsEntry("errorCode", null);
    }
  }

  @Test
  void aFailedPushCarriesItsSafeCodeAndMessage() throws Exception {
    try (Connection listen = listen()) {
      Operation started = start();
      operations.fail(started.id(), 1, ErrorCode.OPERATION_FAILED, "The operation failed");

      Map<String, Object> push = drain(listen, started.id().toString(), 1).getFirst();

      assertThat(push)
          .containsEntry("status", "FAILED")
          .containsEntry("errorCode", ErrorCode.OPERATION_FAILED)
          .containsEntry("errorMessage", "The operation failed");
    }
  }

  @Test
  void aLostConditionalUpdateChangesAndNotifiesNothing() throws Exception {
    try (Connection listen = listen()) {
      Operation started = start();

      assertThatThrownBy(() -> operations.complete(started.id(), 1, Map.of()))
          .isInstanceOf(InvalidOperationTransitionException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
      assertThatThrownBy(() -> operations.markRunning(started.id(), 7))
          .isInstanceOf(InvalidOperationTransitionException.class);

      assertThat(operations.find(started.id()).orElseThrow())
          .returns(OperationStatus.QUEUED, Operation::status)
          .returns(1, Operation::version);
      assertThat(drain(listen, started.id().toString(), 0)).isEmpty();
    }
  }

  @Test
  void aLongErrorMessageIsCutSoThePushStaysUnder8000Bytes() throws Exception {
    try (Connection listen = listen()) {
      Operation started = start();
      Operation failed = operations.fail(started.id(), 1, "PROBE", "x".repeat(20_000));

      assertThat(failed.errorMessage()).hasSize(500);
      PGNotification notification = rawNotifications(listen, started.id().toString(), 1).getFirst();
      assertThat(notification.getParameter().getBytes(StandardCharsets.UTF_8).length)
          .isLessThan(8000);
    }
  }

  private static Connection listen() throws Exception {
    Connection connection = TestDatabase.connectAsApp("seed_operations");
    try (Statement statement = connection.createStatement()) {
      statement.execute("LISTEN operation_status");
    }
    return connection;
  }

  private List<Map<String, Object>> drain(Connection listen, String operationId, int expected)
      throws Exception {
    List<Map<String, Object>> pushes = new ArrayList<>();
    for (PGNotification notification : rawNotifications(listen, operationId, expected)) {
      pushes.add(json.readValue(notification.getParameter(), JSON_OBJECT));
    }
    return pushes;
  }

  /** The notifications about {@code operationId}, waiting up to 5 s for {@code expected}. */
  private static List<PGNotification> rawNotifications(
      Connection listen, String operationId, int expected) throws Exception {
    List<PGNotification> found = new ArrayList<>();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline && (expected == 0 || found.size() < expected)) {
      PGNotification[] batch = listen.unwrap(PGConnection.class).getNotifications(200);
      if (batch != null) {
        for (PGNotification notification : batch) {
          if (notification.getParameter().contains(operationId)) {
            found.add(notification);
          }
        }
      }
      if (expected == 0 && System.nanoTime() > deadline - 4_000_000_000L) {
        break;
      }
    }
    return found;
  }
}
