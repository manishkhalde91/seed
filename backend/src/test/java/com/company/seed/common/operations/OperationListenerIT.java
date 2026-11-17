package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.ProbeOperationBehaviour.Mode;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.jobs.EventPublicationJobs;
import com.company.seed.common.operations.application.OperationListenerSupport;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import java.util.HashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The work of an operation through {@code OperationListenerSupport} (realtime-and-storage.md §1.2,
 * errors-and-logging.md §2): retried up to 3 times, then {@code FAILED}; a permanent failure is not
 * retried; the publication completes either way; a redelivered event finishes or leaves alone the
 * operation it finds.
 */
@ExtendWith(OutputCaptureExtension.class)
class OperationListenerIT extends OperationsTestSupport {

  private static final String USER = "user:listener-it";

  @Autowired private OperationListenerSupport support;
  @Autowired private EventPublicationJobs eventPublicationJobs;

  @Test
  void aWorkThatFailsTwiceSucceedsOnTheThirdAttempt() {
    behaviour.reset(Mode.FAIL_TWICE_THEN_SUCCEED);

    Operation operation = startProbeAs(USER);

    Operation done = awaitStatus(operation.id(), OperationStatus.SUCCEEDED);
    assertThat(behaviour.attempts()).isEqualTo(3);
    assertThat(done.version()).isEqualTo(3);
    assertThat(done.result()).isEqualTo(Map.of("attempt", 3));
    awaitPublicationsCompleted(operation);
  }

  @Test
  void aWorkThatAlwaysFailsEndsFailedAfterFourAttemptsWithOneErrorLine(CapturedOutput output) {
    behaviour.reset(Mode.ALWAYS_FAIL);

    Operation operation = startProbeAs(USER);

    Operation failed = awaitStatus(operation.id(), OperationStatus.FAILED);
    assertThat(behaviour.attempts()).isEqualTo(4);
    assertThat(failed.errorCode()).isEqualTo(ErrorCode.OPERATION_FAILED);
    assertThat(failed.errorMessage()).isEqualTo("The operation failed");
    awaitPublicationsCompleted(operation);
    assertThat(
            EcsLogLines.of(output.getOut(), OperationListenerSupport.class.getName()).stream()
                .filter(line -> line.level().equals("ERROR"))
                .filter(line -> operation.id().toString().equals(line.field("operationId"))))
        .singleElement()
        .satisfies(line -> assertThat(line.message()).contains("after 4 attempt(s)"));
  }

  @Test
  void aPermanentFailureIsNotRetried() {
    behaviour.reset(Mode.PERMANENT_FAILURE);

    Operation operation = startProbeAs(USER);

    Operation failed = awaitStatus(operation.id(), OperationStatus.FAILED);
    assertThat(behaviour.attempts()).isEqualTo(1);
    assertThat(failed.errorCode()).isEqualTo(ErrorCode.OPERATION_FAILED);
    awaitPublicationsCompleted(operation);
  }

  @Test
  void aResultTooLargeToPushFailsTheOperationInsteadOfLeavingItRunning(CapturedOutput output) {
    Operation queued = startProbeAsWithoutWork();

    support.run(queued.id(), USER, "too-large", () -> Map.of("blob", "x".repeat(9_000)));

    assertThat(operations.find(queued.id()).orElseThrow())
        .returns(OperationStatus.FAILED, Operation::status)
        .returns(ErrorCode.OPERATION_FAILED, Operation::errorCode)
        .returns(null, Operation::result);
    assertThat(output.getOut()).contains("its result could not be recorded");
  }

  @Test
  void aResultMayHoldNullMembers() {
    Operation queued = startProbeAsWithoutWork();
    Map<String, Object> result = new HashMap<>();
    result.put("artifactId", null);

    support.run(queued.id(), USER, "nulls", () -> result);

    Operation done = operations.find(queued.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
    assertThat(done.result()).containsEntry("artifactId", null);
  }

  @Test
  void aDeliveryThatLosesTheRaceToAnotherStopsQuietly() {
    Operation queued = startProbeAsWithoutWork();

    assertThatCode(
            () ->
                support.run(
                    queued.id(),
                    USER,
                    "raced",
                    () -> {
                      // Another delivery of the same event finishes first.
                      operations.complete(queued.id(), 2, Map.of("winner", "other"));
                      return Map.of("winner", "this");
                    }))
        .doesNotThrowAnyException();

    assertThat(operations.find(queued.id()).orElseThrow())
        .returns(OperationStatus.SUCCEEDED, Operation::status)
        .returns(Map.of("winner", "other"), Operation::result);
  }

  @Test
  void aPublicationStuckBeforeItsListenerRanIsMarkedStaleAndResubmitted() {
    behaviour.reset(Mode.THROW_FROM_LISTENER);
    Operation operation = startProbeAs(USER);
    awaitPublicationStatus(operation, "FAILED");
    // As if its task had been rejected by the executor: still PUBLISHED, 10 minutes later.
    jdbc.update(
        "UPDATE common.event_publication SET status = 'PUBLISHED',"
            + " publication_date = now() - interval '10 minutes', last_resubmission_date = NULL"
            + " WHERE serialized_event LIKE ?",
        "%" + operation.id() + "%");

    awaitPublicationStatus(operation, "FAILED");
    eventPublicationJobs.resubmit();

    Operation done = awaitStatus(operation.id(), OperationStatus.SUCCEEDED);
    awaitPublicationsCompleted(operation);
    assertThat(done.version()).isEqualTo(3);
    assertThat(publications(operation.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.get("last_resubmission_date")).isNotNull());
  }

  @Test
  void aRedeliveredEventFinishesARunningOperationWithoutMarkingItRunningAgain() {
    Operation queued = startProbeAsWithoutWork();
    Operation running = operations.markRunning(queued.id(), 1);

    support.run(queued.id(), USER, "redelivered", () -> Map.of("resumed", true));

    Operation done = operations.find(queued.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
    assertThat(done.version()).isEqualTo(running.version() + 1);
    assertThat(done.result()).isEqualTo(Map.of("resumed", true));
  }

  @Test
  void aRedeliveredEventLeavesAFinishedOperationAlone() {
    Operation queued = startProbeAsWithoutWork();
    Operation running = operations.markRunning(queued.id(), 1);
    operations.complete(queued.id(), running.version(), Map.of("first", true));

    support.run(
        queued.id(),
        USER,
        "redelivered",
        () -> {
          throw new AssertionError("a finished operation's work must not run again");
        });

    assertThat(operations.find(queued.id()).orElseThrow())
        .returns(OperationStatus.SUCCEEDED, Operation::status)
        .returns(3, Operation::version)
        .returns(Map.of("first", true), Operation::result);
  }

  @Test
  void theResubmitJobDeliversAnIncompletePublicationAgain() {
    behaviour.reset(Mode.THROW_FROM_LISTENER);
    Operation operation = startProbeAs(USER);
    awaitPublicationRecorded(operation);
    // The first delivery threw before any work: the operation is still QUEUED, its publication
    // incomplete. Age it past seed.jobs.event-publication-resubmit-after (5 minutes).
    assertThat(operations.find(operation.id()).orElseThrow().status())
        .isEqualTo(OperationStatus.QUEUED);
    jdbc.update(
        "UPDATE common.event_publication SET publication_date = now() - interval '10 minutes'"
            + " WHERE serialized_event LIKE ?",
        "%" + operation.id() + "%");

    eventPublicationJobs.resubmit();

    awaitStatus(operation.id(), OperationStatus.SUCCEEDED);
    awaitPublicationsCompleted(operation);
  }

  private void awaitPublicationStatus(Operation operation, String status) {
    Awaitility.await()
        .atMost(WAIT)
        .until(
            () -> publications(operation.id()),
            rows ->
                !rows.isEmpty() && rows.stream().allMatch(row -> status.equals(row.get("status"))));
  }

  /** A probe operation whose event the probe's listener ignores: its work is driven by hand. */
  private Operation startProbeAsWithoutWork() {
    return com.company.seed.common.actor.SystemActor.call(
        USER, () -> operations.start("PROBE_BY_HAND", null));
  }
}
