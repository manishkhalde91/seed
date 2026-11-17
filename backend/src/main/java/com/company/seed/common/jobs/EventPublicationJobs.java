package com.company.seed.common.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The jobs that keep Spring Modulith's event publication registry ({@code
 * common.event_publication}, realtime-and-storage.md §3) healthy:
 *
 * <ul>
 *   <li>{@code event-publication-resubmit} hands incomplete publications older than {@code
 *       seed.jobs.event-publication-resubmit-after} to their listeners again. Modulith resubmits
 *       only {@code FAILED} publications: a listener that threw, or one its staleness monitor
 *       marked failed ({@code spring.modulith.events.staleness.*}: a task the executor rejected, or
 *       one lost in a crash without a restart);
 *   <li>{@code event-publication-cleanup} deletes completed publications older than {@code
 *       seed.jobs.event-publication-retention}.
 * </ul>
 */
@Component
@EnableConfigurationProperties(JobsProperties.class)
public class EventPublicationJobs {

  static final String RESUBMIT = "event-publication-resubmit";
  static final String CLEANUP = "event-publication-cleanup";

  private static final Logger LOG = LoggerFactory.getLogger(EventPublicationJobs.class);

  private final ScheduledJob jobs;
  private final IncompleteEventPublications incomplete;
  private final CompletedEventPublications completed;
  private final JobsProperties properties;

  public EventPublicationJobs(
      ScheduledJob jobs,
      IncompleteEventPublications incomplete,
      CompletedEventPublications completed,
      JobsProperties properties) {
    this.jobs = jobs;
    this.incomplete = incomplete;
    this.completed = completed;
    this.properties = properties;
  }

  @Scheduled(cron = "${seed.jobs.event-publication-resubmit-cron:0 */5 * * * *}")
  public void resubmit() {
    jobs.run(
        RESUBMIT,
        () -> {
          incomplete.resubmitIncompletePublicationsOlderThan(
              properties.eventPublicationResubmitAfter());
          LOG.info(
              "Resubmitted incomplete event publications older than {}",
              properties.eventPublicationResubmitAfter());
        });
  }

  @Scheduled(cron = "${seed.jobs.event-publication-cleanup-cron:0 45 3 * * *}")
  public void cleanup() {
    jobs.run(
        CLEANUP,
        () -> {
          completed.deletePublicationsOlderThan(properties.eventPublicationRetention());
          LOG.info(
              "Deleted completed event publications older than {}",
              properties.eventPublicationRetention());
        });
  }
}
