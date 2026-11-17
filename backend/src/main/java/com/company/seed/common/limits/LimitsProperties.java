package com.company.seed.common.limits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code seed.limits.*} (security.md §6), set from the environment in {@code application.yml}.
 * Startup fails on an invalid value, naming the property.
 *
 * @param maxBodySize the largest request body that is not {@code multipart/form-data} ({@code
 *     SEED_MAX_BODY_SIZE}, default 1 MB = 1,048,576 bytes); larger: 413 {@code PAYLOAD_TOO_LARGE}.
 *     Positive and at most {@value #MAX_BODY_SIZE_BOUND} bytes, the largest array a body can be
 *     read into
 * @param writesPerMinute the write budget of one caller on one instance ({@code
 *     SEED_WRITES_PER_MINUTE}, default 60); over it: 429 {@code RATE_LIMITED}. At least 1
 */
@ConfigurationProperties("seed.limits")
public record LimitsProperties(
    @DefaultValue("1MB") DataSize maxBodySize, @DefaultValue("60") int writesPerMinute) {

  /** The largest {@link #maxBodySize()}: a body is read whole into one array, at most this big. */
  public static final long MAX_BODY_SIZE_BOUND = Integer.MAX_VALUE - 8;

  public LimitsProperties {
    if (maxBodySize == null
        || maxBodySize.toBytes() <= 0
        || maxBodySize.toBytes() > MAX_BODY_SIZE_BOUND) {
      throw new IllegalArgumentException(
          "seed.limits.max-body-size must be positive and at most "
              + MAX_BODY_SIZE_BOUND
              + " bytes");
    }
    if (writesPerMinute < 1) {
      throw new IllegalArgumentException("seed.limits.writes-per-minute must be at least 1");
    }
  }

  /** {@link #maxBodySize()} in bytes. */
  public long maxBodyBytes() {
    return maxBodySize.toBytes();
  }

  /** {@link #maxBodySize()} as written in configuration: {@link #text(DataSize)}. */
  public String maxBodySizeText() {
    return text(maxBodySize);
  }

  /**
   * A size as written in configuration ({@code 1MB}, {@code 2KB}, {@code 1500B}): the largest whole
   * unit that divides it.
   */
  public static String text(DataSize size) {
    long bytes = size.toBytes();
    if (bytes > 0 && bytes % DataSize.ofGigabytes(1).toBytes() == 0) {
      return bytes / DataSize.ofGigabytes(1).toBytes() + "GB";
    }
    if (bytes > 0 && bytes % DataSize.ofMegabytes(1).toBytes() == 0) {
      return bytes / DataSize.ofMegabytes(1).toBytes() + "MB";
    }
    if (bytes > 0 && bytes % DataSize.ofKilobytes(1).toBytes() == 0) {
      return bytes / DataSize.ofKilobytes(1).toBytes() + "KB";
    }
    return bytes + "B";
  }
}
