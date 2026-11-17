package com.company.seed.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.operations.OperationsTestSupport;
import com.company.seed.common.operations.infrastructure.scheduling.OperationCleanupJob;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The retention jobs (realtime-and-storage.md §3): finished operations and completed event
 * publications older than 7 days are deleted; unfinished operations, younger rows and incomplete
 * publications stay.
 */
class CleanupJobsIT extends OperationsTestSupport {

  @Autowired private OperationCleanupJob operationCleanup;
  @Autowired private EventPublicationJobs eventPublicationJobs;

  @Test
  void operationCleanupDeletesOnlyFinishedOperationsOlderThanTheRetention() {
    UUID oldSucceeded = operation("SUCCEEDED", "8 days");
    UUID oldFailed = operation("FAILED", "8 days");
    UUID oldQueued = operation("QUEUED", "8 days");
    UUID oldRunning = operation("RUNNING", "8 days");
    UUID recentSucceeded = operation("SUCCEEDED", "1 day");

    operationCleanup.run();

    assertThat(exists("common.operation", oldSucceeded)).isFalse();
    assertThat(exists("common.operation", oldFailed)).isFalse();
    assertThat(exists("common.operation", oldQueued)).isTrue();
    assertThat(exists("common.operation", oldRunning)).isTrue();
    assertThat(exists("common.operation", recentSucceeded)).isTrue();
  }

  @Test
  void eventPublicationCleanupDeletesOnlyCompletedPublicationsOlderThanTheRetention() {
    UUID oldCompleted = publication("8 days", true);
    UUID recentCompleted = publication("1 day", true);
    UUID oldIncomplete = publication("8 days", false);

    eventPublicationJobs.cleanup();

    assertThat(exists("common.event_publication", oldCompleted)).isFalse();
    assertThat(exists("common.event_publication", recentCompleted)).isTrue();
    assertThat(exists("common.event_publication", oldIncomplete)).isTrue();
  }

  private UUID operation(String status, String age) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO common.operation (id, kind, requested_by, status, status_version,"
            + " correlation_id, created_at, created_by, updated_at, updated_by)"
            + " VALUES (?, 'CLEANUP_PROBE', 'user:cleanup', ?, 3, 'c', now() - ?::interval,"
            + " 'user:cleanup', now() - ?::interval, 'user:cleanup')",
        id,
        status,
        age,
        age);
    return id;
  }

  private UUID publication(String age, boolean completed) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO common.event_publication (id, listener_id, event_type, serialized_event,"
            + " publication_date, completion_date, status, completion_attempts)"
            + " VALUES (?, 'cleanup.probe', 'cleanup.Probe', '{}', now() - ?::interval,"
            + " CASE WHEN ? THEN now() - ?::interval END, CASE WHEN ? THEN 'COMPLETED' END, 1)",
        id,
        age,
        completed,
        age,
        completed);
    return id;
  }

  private boolean exists(String table, UUID id) {
    Integer count =
        jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    return count != null && count > 0;
  }
}
