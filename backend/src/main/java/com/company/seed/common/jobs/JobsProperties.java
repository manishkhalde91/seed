package com.company.seed.common.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The retentions of the housekeeping jobs ({@code seed.jobs.*}, realtime-and-storage.md §3). Their
 * cron expressions are read by the {@code @Scheduled} methods themselves: {@code
 * seed.jobs.<job>-cron}, except {@code seed.idempotency.cleanup-cron}. Each job class registers
 * these properties itself, so it does not depend on another job being present.
 *
 * @param operationRetention finished operations created longer ago are deleted
 * @param eventPublicationRetention completed event publications older than this are deleted
 * @param eventPublicationResubmitAfter incomplete publications older than this are resubmitted
 */
@ConfigurationProperties("seed.jobs")
public record JobsProperties(
    @DefaultValue("7d") Duration operationRetention,
    @DefaultValue("7d") Duration eventPublicationRetention,
    @DefaultValue("5m") Duration eventPublicationResubmitAfter) {

  public JobsProperties {
    if (!isPositive(operationRetention)
        || !isPositive(eventPublicationRetention)
        || !isPositive(eventPublicationResubmitAfter)) {
      throw new IllegalArgumentException(
          "seed.jobs.operation-retention, event-publication-retention and"
              + " event-publication-resubmit-after must be positive");
    }
  }

  private static boolean isPositive(Duration duration) {
    return duration != null && duration.isPositive();
  }
}
