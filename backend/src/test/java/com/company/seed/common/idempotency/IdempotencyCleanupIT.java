package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.idempotency.ActorRecordingIdempotencyRecordRepository;
import com.company.fixtures.idempotency.IdempotencyTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The {@code idempotency-cleanup} job: retention by the application clock, run as its system actor,
 * one INFO line. Only this test's rows are asserted on, because the database is shared.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyCleanupIT extends IdempotencyTestSupport {

  private static final String HASH = "c".repeat(64);
  private static final String CALLER = "user:cleanup-it";

  @Autowired private IdempotencyCleanup cleanup;
  @Autowired private ActorRecordingIdempotencyRecordRepository repository;

  @Test
  void deletesRecordsOlderThanTheRetentionAndLogsTheCount(CapturedOutput output) {
    Instant now = clock.instant();
    Instant cutoff = now.minus(Duration.ofHours(24));
    String expired = unique("expired");
    String recent = unique("recent");
    repository.claim(
        CALLER, expired, HASH, UUID.randomUUID(), now.minus(Duration.ofHours(25)), Instant.EPOCH);
    repository.claim(
        CALLER, recent, HASH, UUID.randomUUID(), now.minus(Duration.ofHours(1)), Instant.EPOCH);
    int actorsBefore = repository.retentionActors().size();

    cleanup.run();

    assertThat(rows(expired)).isEmpty();
    assertThat(rows(recent)).hasSize(1);
    assertThat(
            repository.retentionActors().subList(actorsBefore, repository.retentionActors().size()))
        .containsExactly(IdempotencyCleanup.ACTOR);
    assertThat(logMessages(output, IdempotencyCleanup.class, "INFO"))
        .singleElement()
        .asString()
        .matches(
            "Idempotency cleanup deleted [1-9]\\d* record\\(s\\) created before "
                + Pattern.quote(cutoff.toString()));
  }
}
