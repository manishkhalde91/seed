package com.company.seed.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.actor.SystemActor;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code ScheduledJob} (realtime-and-storage.md §3): one instance at a time through a PostgreSQL
 * advisory lock, as {@code system:job:<name>} with a {@code job:<name>:<uuid>} correlation ID, a
 * {@code seed_job_duration_seconds} sample per run, and failures logged once without escaping.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScheduledJobIT extends OperationsTestSupport {

  @Autowired private ScheduledJob jobs;
  @Autowired private MeterRegistry meters;

  @Test
  void aRunSeesTheJobActorAndCorrelationIdAndRecordsItsDuration() {
    AtomicReference<String> actor = new AtomicReference<>();
    AtomicReference<String> correlationId = new AtomicReference<>();

    ScheduledJob.Outcome outcome =
        jobs.run(
            "probe-ok",
            () -> {
              actor.set(SystemActor.current());
              correlationId.set(MDC.get("correlationId"));
            });

    assertThat(outcome).isEqualTo(ScheduledJob.Outcome.SUCCEEDED);
    assertThat(actor).hasValue("system:job:probe-ok");
    assertThat(correlationId.get())
        .matches(
            "job:probe-ok:[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    assertThat(
            meters
                .get("seed.job.duration")
                .tags("job", "probe-ok", "status", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(MDC.get("correlationId")).isNull();
    assertThat(lockIsFree("probe-ok")).isTrue();
    assertThat(
            LenientRestClient.anonymous(managementPort)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class))
        .containsPattern(
            "seed_job_duration_seconds_count\\{[^}]*job=\"probe-ok\"[^}]*status=\"success\"");
  }

  @Test
  void aJobWhoseLockIsHeldElsewhereIsSkipped() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    ScheduledJob.Outcome outcome;
    try (Connection other = TestDatabase.connectAsApp("seed_operations")) {
      lock(other, "probe-locked");

      outcome = jobs.run("probe-locked", () -> ran.set(true));
    }

    assertThat(outcome).isEqualTo(ScheduledJob.Outcome.SKIPPED);
    assertThat(ran).isFalse();
  }

  @Test
  void aFailingRunIsLoggedOnceWithItsSqlStateAndDoesNotEscape(CapturedOutput output) {
    AtomicReference<ScheduledJob.Outcome> outcome = new AtomicReference<>();
    assertThatCode(
            () ->
                outcome.set(
                    jobs.run(
                        "probe-fails",
                        () -> jdbc.queryForObject("SELECT 1 FROM no_such_table", Integer.class))))
        .doesNotThrowAnyException();

    assertThat(outcome).hasValue(ScheduledJob.Outcome.FAILED);

    assertThat(
            EcsLogLines.of(output.getOut(), ScheduledJob.class.getName()).stream()
                .filter(line -> line.level().equals("ERROR")))
        .singleElement()
        .satisfies(line -> assertThat(line.message()).contains("probe-fails").contains("42P01"));
    assertThat(
            meters
                .get("seed.job.duration")
                .tags("job", "probe-fails", "status", "failure")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(lockIsFree("probe-fails")).isTrue();
  }

  @Test
  void theLockIsReleasedWhenTheBodyThrowsAnError() {
    assertThatThrownBy(
            () ->
                jobs.run(
                    "probe-error",
                    () -> {
                      throw new AssertionError("an Error, not an exception");
                    }))
        .isInstanceOf(AssertionError.class);

    assertThat(lockIsFree("probe-error")).isTrue();
  }

  private static void lock(Connection connection, String name) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
      statement.setString(1, "job:" + name);
      statement.execute();
    }
  }

  /** Whether another session can take the job's lock now (and gives it back at once). */
  private static boolean lockIsFree(String name) {
    try (Connection other = TestDatabase.connectAsApp("seed_operations");
        PreparedStatement statement =
            other.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
      statement.setString(1, "job:" + name);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
