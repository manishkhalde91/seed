package com.company.seed.common.operations.infrastructure.ws;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The STOMP contract's destinations (api-standards.md §9) and the session attributes that the
 * {@code common} and {@code security} sides of the endpoint share. {@code common} never imports
 * {@code security}, so the keys live here.
 */
public final class StompSessions {

  /** The WebSocket endpoint (plain WebSocket, no SockJS). */
  public static final String ENDPOINT = "/ws";

  /** The prefix of the snapshot destinations, answered by {@code @SubscribeMapping} handlers. */
  public static final String APPLICATION_PREFIX = "/app";

  /** The prefix of the broker's destinations. */
  public static final String TOPIC_PREFIX = "/topic";

  /** The live status of operation {@code <id>}: {@code /topic/operations/<id>}. */
  public static final String OPERATION_TOPIC_PREFIX = TOPIC_PREFIX + "/operations/";

  /** The snapshot of operation {@code <id>}: {@code /app/operations/<id>}. */
  public static final String OPERATION_SNAPSHOT_PREFIX = APPLICATION_PREFIX + "/operations/";

  /** Where a listener that may have missed notifications says so. */
  public static final String RESYNC_TOPIC = TOPIC_PREFIX + "/system/resync";

  /** Session attribute: the session's correlation ID, from {@code CONNECT} (a {@code String}). */
  public static final String CORRELATION_ID = StompSessions.class.getName() + ".correlationId";

  /**
   * Session attribute: when the token presented on {@code CONNECT} expires (an {@link
   * java.time.Instant}). Set by the security side; the session is closed with {@value
   * StompErrors#TOKEN_EXPIRED} once it has passed.
   */
  public static final String TOKEN_EXPIRES_AT = StompSessions.class.getName() + ".tokenExpiresAt";

  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private StompSessions() {}

  /** The status topic of operation {@code id}. */
  public static String operationTopic(UUID id) {
    return OPERATION_TOPIC_PREFIX + id;
  }

  /**
   * The operation a status topic or snapshot destination names. Empty when {@code destination} is
   * neither; an ID that is not a UUID in its canonical (lower-case) form is {@link
   * OperationReference#id() id} {@code null}, so wildcards and look-alikes never match.
   */
  public static Optional<OperationReference> operationOf(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    String raw;
    boolean topic;
    if (destination.startsWith(OPERATION_TOPIC_PREFIX)) {
      raw = destination.substring(OPERATION_TOPIC_PREFIX.length());
      topic = true;
    } else if (destination.startsWith(OPERATION_SNAPSHOT_PREFIX)) {
      raw = destination.substring(OPERATION_SNAPSHOT_PREFIX.length());
      topic = false;
    } else {
      return Optional.empty();
    }
    return Optional.of(new OperationReference(canonicalUuid(raw).orElse(null), topic));
  }

  /** {@code value} as a UUID when it is one in canonical (lower-case, hyphenated) form. */
  public static Optional<UUID> canonicalUuid(String value) {
    return value != null && CANONICAL_UUID.matcher(value).matches()
        ? Optional.of(UUID.fromString(value))
        : Optional.empty();
  }

  /** The session's correlation ID, or {@code null} before {@code CONNECT}. */
  public static String correlationId(Map<String, Object> sessionAttributes) {
    return sessionAttributes != null
            && sessionAttributes.get(CORRELATION_ID) instanceof String correlationId
        ? correlationId
        : null;
  }

  /**
   * An operation named by a destination.
   *
   * @param id the operation's ID; {@code null} when the destination's ID is not a canonical UUID
   * @param topic the status topic ({@code true}) or the snapshot ({@code false})
   */
  public record OperationReference(UUID id, boolean topic) {}
}
