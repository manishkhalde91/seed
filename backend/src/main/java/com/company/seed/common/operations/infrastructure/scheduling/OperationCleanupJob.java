package com.company.seed.common.operations.infrastructure.scheduling;

import com.company.seed.common.jobs.JobsProperties;
import com.company.seed.common.jobs.ScheduledJob;
import com.company.seed.common.operations.domain.OperationRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code operation-cleanup} job (realtime-and-storage.md §3): deletes finished operations
 * created more than {@code seed.jobs.operation-retention} ago. Unfinished ones stay, so a late
 * resubmission still finds its operation.
 */
@Component
@EnableConfigurationProperties(JobsProperties.class)
public class OperationCleanupJob {

  static final String NAME = "operation-cleanup";

  private static final Logger LOG = LoggerFactory.getLogger(OperationCleanupJob.class);

  private final ScheduledJob jobs;
  private final OperationRepository repository;
  private final JobsProperties properties;
  private final Clock clock;

  public OperationCleanupJob(
      ScheduledJob jobs, OperationRepository repository, JobsProperties properties, Clock clock) {
    this.jobs = jobs;
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  /** Runs on {@code seed.jobs.operation-cleanup-cron} ({@code -} turns it off). */
  @Scheduled(cron = "${seed.jobs.operation-cleanup-cron:0 30 3 * * *}")
  public void run() {
    jobs.run(
        NAME,
        () -> {
          Instant cutoff = clock.instant().minus(properties.operationRetention());
          int deleted = repository.deleteFinishedCreatedBefore(cutoff);
          LOG.info("Operation cleanup deleted {} operation(s) created before {}", deleted, cutoff);
        });
  }
}
