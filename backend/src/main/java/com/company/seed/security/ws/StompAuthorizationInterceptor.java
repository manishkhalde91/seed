package com.company.seed.security.ws;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.logging.LogSafe;
import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import com.company.seed.common.operations.infrastructure.ws.StompErrors;
import com.company.seed.common.operations.infrastructure.ws.StompRejectedException;
import com.company.seed.common.operations.infrastructure.ws.StompSessionRegistry;
import com.company.seed.common.operations.infrastructure.ws.StompSessions;
import com.company.seed.common.operations.infrastructure.ws.StompSessions.OperationReference;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Authenticates and authorises client frames before they are dispatched (api-standards.md §9,
 * security.md §3-§4). A refused frame is dropped and the session gets the {@code ERROR} frame of
 * its code, sent here through {@link StompSessionRegistry#closeWithError}, after which it closes.
 * (Receive order is preserved, and the ordered channel only logs what an interceptor throws, so a
 * refusal must not be thrown.) An unexpected failure is logged once at ERROR and refused with
 * {@value StompErrors#INTERNAL_ERROR}.
 *
 * <ul>
 *   <li>{@code CONNECT}: {@code Authorization: Bearer <jwt>} is decoded with the application's
 *       {@link JwtDecoder} and {@link JwtAuthenticationConverter}, so issuer, audience, {@code
 *       exp}/{@code nbf} with skew, subject and roles follow the REST rules. The caller becomes the
 *       session's user and the token's {@code exp} a session attribute ({@link
 *       StompSessions#TOKEN_EXPIRES_AT}). A missing or invalid token, or a second {@code CONNECT}
 *       on the session: {@value StompErrors#UNAUTHORIZED}; a token whose {@code exp} has passed
 *       (accepted by the decoder's clock skew): {@value StompErrors#TOKEN_EXPIRED}.
 *   <li>Any other frame needs that user ({@value StompErrors#UNAUTHORIZED}) and a token that has
 *       not expired since ({@value StompErrors#TOKEN_EXPIRED}); {@code DISCONNECT} and heart-beats
 *       always pass.
 *   <li>{@code SUBSCRIBE} to {@code /topic/operations/{id}} or {@code /app/operations/{id}}: the
 *       operation's requester or an {@code ADMIN} ({@link OperationService#getForCurrentActor}, as
 *       for {@code GET /api/v1/operations/{id}}); an unknown or non-UUID ID is {@value
 *       StompErrors#NOT_FOUND}, another caller {@value StompErrors#FORBIDDEN_DESTINATION}. {@code
 *       /topic/system/resync} is open to every session; any other destination is {@value
 *       StompErrors#FORBIDDEN_DESTINATION}.
 *   <li>A session holds at most {@value #MAX_SUBSCRIPTIONS} broker subscriptions ({@code /topic});
 *       the next is {@value StompErrors#TOO_MANY_SUBSCRIPTIONS}, and {@code UNSUBSCRIBE} frees a
 *       slot. A snapshot subscription ({@code /app}) is answered once and holds no slot.
 *   <li>{@code SEND} is {@value StompErrors#FORBIDDEN_DESTINATION}: the channel is push-only.
 * </ul>
 *
 * Runs after {@code SecurityContextChannelInterceptor}, so the subscription check sees the
 * session's user as the security context.
 */
final class StompAuthorizationInterceptor implements ChannelInterceptor {

  static final int MAX_SUBSCRIPTIONS = 50;

  /** Session attribute: the IDs of the session's broker subscriptions. */
  static final String SUBSCRIPTIONS = StompAuthorizationInterceptor.class.getName() + ".ids";

  private static final String BEARER = "Bearer ";

  private static final Logger LOG = LoggerFactory.getLogger(StompAuthorizationInterceptor.class);

  private final JwtDecoder jwtDecoder;
  private final JwtAuthenticationConverter authenticationConverter;
  private final OperationService operations;
  private final StompSessionRegistry sessions;
  private final Clock clock;

  StompAuthorizationInterceptor(
      JwtDecoder jwtDecoder,
      JwtAuthenticationConverter authenticationConverter,
      OperationService operations,
      StompSessionRegistry sessions,
      Clock clock) {
    this.jwtDecoder = jwtDecoder;
    this.authenticationConverter = authenticationConverter;
    this.operations = operations;
    this.sessions = sessions;
    this.clock = clock;
  }

  /** The frame, or {@code null} (dropped) once its {@code ERROR} frame has been sent. */
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    StompCommand command = accessor == null ? null : accessor.getCommand();
    if (command == null || command == StompCommand.DISCONNECT) {
      return message;
    }
    Optional<String> refusal = refusal(accessor, command);
    if (refusal.isEmpty()) {
      return message;
    }
    sessions.closeWithError(accessor.getSessionId(), refusal.get());
    return null;
  }

  /** The {@code ERROR} code refusing the frame; empty when it may pass. */
  private Optional<String> refusal(StompHeaderAccessor accessor, StompCommand command) {
    try {
      check(accessor, command);
      return Optional.empty();
    } catch (StompRejectedException rejected) {
      return Optional.of(rejected.code());
    } catch (RuntimeException unexpected) {
      LOG.error(
          "STOMP {} frame failed; the session gets ERROR {}",
          command,
          StompErrors.INTERNAL_ERROR,
          unexpected);
      return Optional.of(StompErrors.INTERNAL_ERROR);
    }
  }

  private void check(StompHeaderAccessor accessor, StompCommand command) {
    if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
      authenticate(accessor);
      return;
    }
    if (accessor.getUser() == null) {
      throw reject(StompErrors.UNAUTHORIZED, "no authenticated CONNECT", command.name());
    }
    if (accessor.getSessionAttributes() != null
        && accessor.getSessionAttributes().get(StompSessions.TOKEN_EXPIRES_AT)
            instanceof Instant expiresAt
        && hasPassed(expiresAt)) {
      throw reject(StompErrors.TOKEN_EXPIRED, "the session's token has expired", command.name());
    }
    switch (command) {
      case SUBSCRIBE -> authorizeSubscription(accessor);
      case UNSUBSCRIBE -> release(accessor);
      case SEND ->
          throw reject(
              StompErrors.FORBIDDEN_DESTINATION,
              "SEND on a push-only channel",
              LogSafe.clean(accessor.getDestination()));
      default -> {
        // ACK, NACK and transaction frames change nothing on the simple broker.
      }
    }
  }

  private void authenticate(StompHeaderAccessor accessor) {
    if (accessor.getUser() != null) {
      throw reject(StompErrors.UNAUTHORIZED, "the session is already connected", "CONNECT");
    }
    String token = bearerToken(accessor);
    if (token == null) {
      throw reject(StompErrors.UNAUTHORIZED, "no bearer token", "CONNECT");
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(token);
    } catch (JwtException invalid) {
      throw reject(StompErrors.UNAUTHORIZED, "invalid token", "CONNECT");
    }
    if (jwt.getExpiresAt() != null && hasPassed(jwt.getExpiresAt())) {
      throw reject(
          StompErrors.TOKEN_EXPIRED, "the token has expired (within clock skew)", "CONNECT");
    }
    AbstractAuthenticationToken authentication = authenticationConverter.convert(jwt);
    accessor.setUser(authentication);
    var attributes = accessor.getSessionAttributes();
    if (attributes != null) {
      attributes.put(StompSessions.TOKEN_EXPIRES_AT, jwt.getExpiresAt());
    }
    MDC.put(CurrentActor.MDC_KEY, jwt.getSubject());
    LOG.info("STOMP session {} connected", accessor.getSessionId());
  }

  private boolean hasPassed(Instant instant) {
    return !clock.instant().isBefore(instant);
  }

  /** The token of {@code Authorization: Bearer <token>}, or {@code null}. */
  private static String bearerToken(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
    if (header == null) {
      header = accessor.getFirstNativeHeader("authorization");
    }
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    String token = header.substring(BEARER.length()).strip();
    return token.isEmpty() ? null : token;
  }

  private void authorizeSubscription(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    if (StompSessions.RESYNC_TOPIC.equals(destination)) {
      hold(accessor);
      return;
    }
    Optional<OperationReference> operation = StompSessions.operationOf(destination);
    if (operation.isEmpty()) {
      throw reject(
          StompErrors.FORBIDDEN_DESTINATION,
          "not a destination of the contract",
          LogSafe.clean(destination));
    }
    if (operation.get().id() == null) {
      throw reject(StompErrors.NOT_FOUND, "not an operation ID", LogSafe.clean(destination));
    }
    if (operation.get().topic()) {
      requireFreeSlot(accessor);
    }
    try {
      operations.getForCurrentActor(operation.get().id());
    } catch (OperationNotFoundException unknown) {
      throw reject(StompErrors.NOT_FOUND, "no such operation", destination);
    } catch (ForbiddenOperationException notTheirs) {
      LOG.warn("STOMP subscription to {} denied: not the requester or an ADMIN", destination);
      throw new StompRejectedException(StompErrors.FORBIDDEN_DESTINATION);
    }
    if (operation.get().topic()) {
      hold(accessor);
    }
  }

  private static void requireFreeSlot(StompHeaderAccessor accessor) {
    Set<String> held = subscriptions(accessor);
    String id = accessor.getSubscriptionId();
    if (held.size() >= MAX_SUBSCRIPTIONS && (id == null || !held.contains(id))) {
      throw reject(
          StompErrors.TOO_MANY_SUBSCRIPTIONS,
          "the session holds the most subscriptions allowed",
          String.valueOf(held.size()));
    }
  }

  /** Counts a broker subscription against the session's limit. */
  private static void hold(StompHeaderAccessor accessor) {
    requireFreeSlot(accessor);
    String id = accessor.getSubscriptionId();
    if (id != null) {
      subscriptions(accessor).add(id);
    }
  }

  private static void release(StompHeaderAccessor accessor) {
    String id = accessor.getSubscriptionId();
    if (id != null) {
      subscriptions(accessor).remove(id);
    }
  }

  private static Set<String> subscriptions(StompHeaderAccessor accessor) {
    var attributes = accessor.getSessionAttributes();
    if (attributes == null) {
      return new HeldSubscriptions().ids();
    }
    return ((HeldSubscriptions)
            attributes.computeIfAbsent(SUBSCRIPTIONS, key -> new HeldSubscriptions()))
        .ids();
  }

  /** The IDs of a session's broker subscriptions, kept as a session attribute. */
  private record HeldSubscriptions(Set<String> ids) {

    HeldSubscriptions() {
      this(ConcurrentHashMap.newKeySet());
    }
  }

  /**
   * Logs an expected refusal at INFO ({@code subject}: the frame or destination) and returns the
   * exception that refuses the frame.
   */
  private static StompRejectedException reject(String code, String reason, String subject) {
    LOG.info("STOMP frame refused with {}: {} ({})", code, reason, subject);
    return new StompRejectedException(code);
  }
}
