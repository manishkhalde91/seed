package com.company.seed.common.idempotency;

import com.company.seed.common.jobs.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code idempotency-cleanup} job (realtime-and-storage.md §3): deletes the records created
 * more than {@code seed.idempotency.retention} ago, by the application {@link Clock}. It runs
 * through {@link ScheduledJob} (one instance at a time, as {@value #ACTOR}). Scheduling is enabled
 * by {@code config.SchedulingConfig}, outside the {@code migrate} profile.
 */
@Component
public class IdempotencyCleanup {

  /** The actor the job runs as. */
  public static final String ACTOR = ScheduledJob.ACTOR_PREFIX + "idempotency-cleanup";

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCleanup.class);

  static final String NAME = "idempotency-cleanup";

  private final ScheduledJob jobs;
  private final IdempotencyRecordRepository repository;
  private final IdempotencyProperties properties;
  private final Clock clock;

  public IdempotencyCleanup(
      ScheduledJob jobs,
      IdempotencyRecordRepository repository,
      IdempotencyProperties properties,
      Clock clock) {
    this.jobs = jobs;
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Runs on {@code seed.idempotency.cleanup-cron} (Spring cron, default hourly on the hour; {@code
   * -} turns the job off).
   */
  @Scheduled(cron = "${seed.idempotency.cleanup-cron:0 0 * * * *}")
  public void run() {
    jobs.run(
        NAME,
        () -> {
          Instant cutoff = clock.instant().minus(properties.retention());
          int deleted = repository.deleteCreatedBefore(cutoff);
          LOG.atInfo()
              .addKeyValue("seed.idempotency.deleted", deleted)
              .log("Idempotency cleanup deleted {} record(s) created before {}", deleted, cutoff);
        });
  }
}
