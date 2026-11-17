/**
 * Housekeeping jobs (realtime-and-storage.md §3): {@link com.company.seed.common.jobs.ScheduledJob}
 * runs each {@code @Scheduled} body on one instance at a time, as {@code system:job:<name>}, with a
 * correlation ID of its own and the {@code seed_job_duration_seconds} metric.
 */
package com.company.seed.common.jobs;
