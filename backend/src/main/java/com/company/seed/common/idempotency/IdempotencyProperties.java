package com.company.seed.common.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code seed.idempotency.*} (api-standards.md §1). The cleanup schedule, {@code
 * seed.idempotency.cleanup-cron}, is read by {@link IdempotencyCleanup} itself.
 *
 * @param retention how long a record is kept, from its creation (default 24 h); an older record is
 *     treated as absent even before the cleanup deletes it
 * @param abandonAfter how long an {@code IN_PROGRESS} claim blocks duplicates before a duplicate
 *     may take it over (default 5 min); must exceed the longest write, or a slow write can run
 *     twice
 * @param maxStoredResponseSize the largest response body that is stored (larger: sent but not
 *     stored, so a retry runs again); default 1 MB. The request body has only the global limit,
 *     {@code seed.limits.max-body-size}
 */
@ConfigurationProperties("seed.idempotency")
public record IdempotencyProperties(
    @DefaultValue("24h") Duration retention,
    @DefaultValue("5m") Duration abandonAfter,
    @DefaultValue("1MB") DataSize maxStoredResponseSize) {

  public IdempotencyProperties {
    if (!isPositive(abandonAfter)
        || !isPositive(retention)
        || retention.compareTo(abandonAfter) <= 0) {
      throw new IllegalArgumentException(
          "seed.idempotency.abandon-after and retention must be positive, and retention longer"
              + " than abandon-after");
    }
    if (maxStoredResponseSize == null
        || maxStoredResponseSize.toBytes() <= 0
        || maxStoredResponseSize.toBytes() >= Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "seed.idempotency.max-stored-response-size must be positive and below 2 GB");
    }
  }

  /** {@link #maxStoredResponseSize()} in bytes. */
  int maxStoredResponseBytes() {
    return (int) maxStoredResponseSize.toBytes();
  }

  private static boolean isPositive(Duration duration) {
    return duration != null && duration.isPositive();
  }
}
