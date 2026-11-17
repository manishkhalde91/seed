package com.company.seed.common.operations.infrastructure.ws;

import com.company.seed.common.api.ApiResponses;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * The open STOMP sessions of this instance (realtime-and-storage.md §1.3).
 *
 * <ul>
 *   <li>{@value #METRIC_NAME} ({@code seed_ws_sessions_active}): the sessions whose {@code CONNECT}
 *       was accepted and that are still open.
 *   <li>Every {@code seed.realtime.token-check-interval} (default {@code 30s}), a session whose
 *       token's {@code exp} has passed (no clock skew) gets {@code ERROR} {@value
 *       StompErrors#TOKEN_EXPIRED} and is closed.
 *   <li>When the application context closes, before the web server and the broker stop, every open
 *       session is closed with status 1012 (service restart), so clients reconnect elsewhere.
 * </ul>
 *
 * Its decorator sees every WebSocket session of {@code /ws}; {@link #closeWithError} sends the
 * {@code ERROR} frame through the client outbound channel, after which Spring closes the session.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StompSessionRegistry implements WebSocketHandlerDecoratorFactory {

  /** The gauge of connected STOMP sessions. */
  public static final String METRIC_NAME = "seed.ws.sessions.active";

  private static final Logger LOG = LoggerFactory.getLogger(StompSessionRegistry.class);

  private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
  private final Set<String> connected = ConcurrentHashMap.newKeySet();
  private final MessageChannel clientOutboundChannel;
  private final Clock clock;
  private final ApplicationContext context;
  private volatile boolean closing;

  StompSessionRegistry(
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
      Clock clock,
      MeterRegistry meters,
      ApplicationContext context) {
    this.clientOutboundChannel = clientOutboundChannel;
    this.clock = clock;
    this.context = context;
    Gauge.builder(METRIC_NAME, connected, Set::size)
        .description("Connected STOMP sessions")
        .register(meters);
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {

      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        open.put(session.getId(), session);
        super.afterConnectionEstablished(session);
        if (closing) {
          // Opened after shutdown began: it would only be cut off when the server stops.
          closeForRestart(session).ifPresent(failure -> logNotClosed(session, failure));
        }
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
          throws Exception {
        open.remove(session.getId());
        connected.remove(session.getId());
        super.afterConnectionClosed(session, closeStatus);
      }
    };
  }

  @EventListener
  void onConnected(SessionConnectedEvent event) {
    String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
    if (sessionId != null) {
      connected.add(sessionId);
      // Closed while CONNECTED was on its way: the close has already been counted.
      if (!open.containsKey(sessionId)) {
        connected.remove(sessionId);
      }
    }
  }

  @EventListener
  void onDisconnect(SessionDisconnectEvent event) {
    connected.remove(event.getSessionId());
  }

  /** Sends {@code ERROR} {@code code} to the session; Spring then closes it. */
  public void closeWithError(String sessionId, String code) {
    clientOutboundChannel.send(StompErrors.errorFrame(code, sessionId));
  }

  /**
   * Closes, with {@value StompErrors#TOKEN_EXPIRED}, every session whose token has expired. A
   * session whose {@code ERROR} could not be sent is tried again at the next check; it never stops
   * the others.
   */
  @Scheduled(fixedDelayString = "${seed.realtime.token-check-interval:30s}")
  public void closeExpiredSessions() {
    Instant now = clock.instant();
    for (WebSocketSession session : open.values()) {
      Map<String, Object> attributes = session.getAttributes();
      if (attributes.get(StompSessions.TOKEN_EXPIRES_AT) instanceof Instant expiresAt
          && !now.isBefore(expiresAt)) {
        closeExpired(session, expiresAt)
            .ifPresent(
                failure ->
                    LOG.warn(
                        "STOMP session {} with an expired token could not be closed ({});"
                            + " retrying at the next check",
                        session.getId(),
                        failure.toString()));
      }
    }
  }

  /** Sends {@code token-expired}; the failure when it could not be sent. */
  private Optional<RuntimeException> closeExpired(WebSocketSession session, Instant expiresAt) {
    Map<String, Object> attributes = session.getAttributes();
    String correlationId = StompSessions.correlationId(attributes);
    try {
      if (correlationId != null) {
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
      }
      closeWithError(session.getId(), StompErrors.TOKEN_EXPIRED);
      attributes.remove(StompSessions.TOKEN_EXPIRES_AT);
      LOG.info("STOMP session {} closed: its token expired at {}", session.getId(), expiresAt);
      return Optional.empty();
    } catch (RuntimeException notSent) {
      return Optional.of(notSent);
    } finally {
      MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY);
    }
  }

  /**
   * Closes every open session with 1012 when this context closes: the event comes before any
   * lifecycle bean stops, so before the broker would close them with 1001 and before the web server
   * stops. A session opened after this is closed with 1012 as soon as it opens.
   */
  @EventListener
  void onContextClosed(ContextClosedEvent event) {
    if (event.getApplicationContext() != context) {
      return;
    }
    closing = true;
    if (open.isEmpty()) {
      return;
    }
    LOG.info("Closing {} STOMP session(s) for shutdown (1012)", open.size());
    for (WebSocketSession session : open.values()) {
      closeForRestart(session).ifPresent(failure -> logNotClosed(session, failure));
    }
  }

  /** The transport already failed; the broker drops the session when it stops. */
  private static void logNotClosed(WebSocketSession session, Exception failure) {
    LOG.warn("STOMP session {} could not be closed with 1012 ({})", session.getId(), failure);
  }

  /** Closes the session with 1012; the failure when its transport could not send the close. */
  private static Optional<Exception> closeForRestart(WebSocketSession session) {
    try {
      session.close(CloseStatus.SERVICE_RESTARTED);
      return Optional.empty();
    } catch (IOException | RuntimeException transportFailed) {
      return Optional.of(transportFailed);
    }
  }
}
