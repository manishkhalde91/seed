package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.StompTestClient;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.operations.api.OperationStatusSubscriptionController;
import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A snapshot ({@code SUBSCRIBE /app/operations/{id}}) that fails after its subscription was
 * allowed: the handler's {@code @MessageExceptionHandler} sends the {@code ERROR} frame and the
 * connection closes. The first {@code getForCurrentActor} (the subscription check) runs for real,
 * the second (the snapshot) fails as stubbed.
 */
@ExtendWith(OutputCaptureExtension.class)
class OperationStatusSnapshotFailureIT extends OperationsTestSupport {

  @MockitoSpyBean private OperationService spiedOperations;

  @Test
  void anOperationGoneBeforeTheSnapshotIsNotFound() {
    Operation started = startForTestUser("SNAPSHOT_GONE", "gone-" + UUID.randomUUID());
    doCallRealMethod()
        .doThrow(new OperationNotFoundException(started.id()))
        .when(spiedOperations)
        .getForCurrentActor(started.id());
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.subscribe("snap", "/app/operations/" + started.id());

      assertThat(client.awaitErrorAndClose()).isEqualTo("not-found");
    }
  }

  @Test
  void anUnexpectedFailureIsAnInternalErrorLoggedOnceWithTheSessionsCorrelationId(
      CapturedOutput output) {
    Operation started = startForTestUser("SNAPSHOT_FAILS", "fails-" + UUID.randomUUID());
    String correlationId = "snapshot-" + UUID.randomUUID();
    doCallRealMethod()
        .doThrow(new IllegalStateException("stubbed snapshot failure"))
        .when(spiedOperations)
        .getForCurrentActor(started.id());
    try (StompTestClient client = StompTestClient.open(port)) {
      client.sendConnect(
          Map.of(
              "Authorization", TestJwt.bearer(TestJwt.user()), "X-Correlation-Id", correlationId));
      assertThat(client.next().command()).isEqualTo("CONNECTED");

      client.subscribe("snap", "/app/operations/" + started.id());

      assertThat(client.awaitErrorAndClose()).isEqualTo("internal-error");
    }
    assertThat(EcsLogLines.all(output.getAll()))
        .filteredOn(line -> line.level().equals("ERROR"))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.logger())
                  .isEqualTo(OperationStatusSubscriptionController.class.getName());
              assertThat(line.field(ApiResponses.CORRELATION_ID_MDC_KEY)).isEqualTo(correlationId);
              assertThat(line.stackTrace()).contains("stubbed snapshot failure");
            });
  }
}
