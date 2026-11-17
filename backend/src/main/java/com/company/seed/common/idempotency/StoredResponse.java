package com.company.seed.common.idempotency;

import java.util.Arrays;
import java.util.Objects;

/**
 * The parts of a response that a replay repeats: status, {@code Content-Type}, {@code Location}
 * (both may be {@code null}) and the body bytes. No other header is stored.
 */
public record StoredResponse(int status, String contentType, String location, byte[] body) {

  public StoredResponse {
    body = Objects.requireNonNull(body, "body").clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StoredResponse that
        && status == that.status
        && Objects.equals(contentType, that.contentType)
        && Objects.equals(location, that.location)
        && Arrays.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, contentType, location, Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "StoredResponse[status=%d, contentType=%s, location=%s, body=%d bytes]"
        .formatted(status, contentType, location, body.length);
  }
}
