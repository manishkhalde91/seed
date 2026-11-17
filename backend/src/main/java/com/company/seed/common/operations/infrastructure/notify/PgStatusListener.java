package com.company.seed.common.operations.infrastructure.notify;

import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.logging.LogSafe;
import com.company.seed.common.operations.application.OperationListenerSupport;
import com.company.seed.common.operations.infrastructure.ws.StompSessions;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns {@code operation_status} notifications into STOMP pushes (realtime-and-storage.md §1.2):
 * every web instance runs one, so a status change made on any instance reaches the clients of all
 * of them without sticky sessions.
 *
 * <ul>
 *   <li>It holds one dedicated connection, outside the Hikari pool, made with the data source's
 *       URL, user and password (application name {@value #APPLICATION_NAME}), and runs {@code
 *       LISTEN operation_status} on a thread of its own.
 *   <li>Each notification's payload (the push of {@code PgOperationStatusNotifier}) is sent
 *       unchanged, as {@code application/json}, to {@code /topic/operations/{operationId}}. A
 *       payload that cannot be read (logged at WARN with its length only) or a push that cannot be
 *       sent (logged at WARN) is skipped; the connection carries on.
 *   <li>When the connection drops it reconnects after 1 s, doubling up to 30 s ({@link
 *       ExponentialBackOff}); a failed attempt, the resync's included, just waits longer. A
 *       connection made after a drop (or after a failed first attempt) publishes {@code
 *       {"reason":"listener-reconnected"}} on {@value StompSessions#RESYNC_TOPIC}: clients re-read
 *       the snapshots they follow, as notifications may have been missed meanwhile.
 *   <li>The first connection is made while the application starts, so an instance whose database is
 *       up reports {@link #isListening() listening} (health {@code operationStatusListener}, part
 *       of readiness) before it takes traffic; a failure there does not stop startup.
 * </ul>
 *
 * Only in a servlet web application, so never in the {@code migrate} run.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PgStatusListener implements SmartLifecycle {

  /**
   * The {@code application_name} of the LISTEN connection, as {@code pg_stat_activity} shows it.
   */
  public static final String APPLICATION_NAME = "seed-operation-status-listener";

  static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
  static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  /** 1 s, doubling, at most 30 s, without end. */
  private static final ExponentialBackOff BACK_OFF = backOff();

  /** How long one wait for notifications blocks, so a stop is noticed quickly. */
  private static final int POLL_MILLIS = 500;

  /** How often an idle connection is checked, so a silently dropped one is noticed. */
  private static final Duration VALIDATE_EVERY = Duration.ofSeconds(10);

  private static final int VALIDATE_TIMEOUT_SECONDS = 5;
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  private static final Map<String, String> RESYNC = Map.of("reason", "listener-reconnected");

  private static final Logger LOG = LoggerFactory.getLogger(PgStatusListener.class);

  private final DataSourceProperties dataSource;
  private final SimpMessageSendingOperations messaging;
  private final JsonMapper json;

  private volatile boolean running;
  private volatile boolean listening;
  private volatile Connection connection;
  private Thread thread;

  public PgStatusListener(
      DataSourceProperties dataSource, SimpMessageSendingOperations messaging, JsonMapper json) {
    this.dataSource = dataSource;
    this.messaging = messaging;
    this.json = json;
  }

  private static ExponentialBackOff backOff() {
    ExponentialBackOff backOff = new ExponentialBackOff(FIRST_BACKOFF.toMillis(), 2.0);
    backOff.setMaxInterval(MAX_BACKOFF.toMillis());
    return backOff;
  }

  /** Whether the dedicated connection is up and listening. */
  public boolean isListening() {
    return listening;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    if (thread != null && thread.isAlive() && !awaitEnd(thread)) {
      // A thread a timed-out stop() left behind sees running again and carries on listening.
      LOG.warn("The previous operation status listener thread is still running; reusing it");
      return;
    }
    Connected first = connect();
    thread =
        Thread.ofPlatform()
            .name("operation-status-listener")
            .daemon(true)
            .start(() -> listen(first.connection()));
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    listening = false;
    closeConnection();
    thread.interrupt();
    awaitEnd(thread);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Waits for {@code previous} to end; {@code false} when it is still alive. */
  private static boolean awaitEnd(Thread previous) {
    try {
      return previous.join(STOP_TIMEOUT);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return !previous.isAlive();
    }
  }

  /**
   * Listens until stopped, reconnecting with backoff; {@code current} is the first connection
   * ({@code null} when the first attempt failed).
   */
  private void listen(Connection current) {
    BackOffExecution backOff = BACK_OFF.start();
    try {
      while (running) {
        if (current == null) {
          Thread.sleep(backOff.nextBackOff());
          Connected attempt = reconnect();
          if (attempt.failure() instanceof RuntimeException failure) {
            LOG.warn("Reconnecting the operation status listener failed ({})", failure.toString());
          }
          current = attempt.connection();
          if (current == null) {
            continue;
          }
          backOff = BACK_OFF.start();
        }
        Optional<SQLException> dropped = pump(current);
        listening = false;
        closeConnection();
        current = null;
        if (dropped.isPresent() && running) {
          LOG.warn(
              "The operation status LISTEN connection dropped (SQLSTATE {}); reconnecting from {}"
                  + " s",
              dropped.get().getSQLState(),
              FIRST_BACKOFF.toSeconds());
        }
      }
    } catch (InterruptedException stopped) {
      // stop() interrupts a backoff wait.
      Thread.currentThread().interrupt();
    } finally {
      // A connection opened while stop() ran is closed here.
      listening = false;
      closeConnection();
    }
  }

  /**
   * Connects again and publishes the resync. Any failure, the resync's included, is a failed
   * attempt: the connection is closed and the listener keeps backing off.
   */
  private Connected reconnect() {
    try {
      Connected attempt = connect();
      if (attempt.connection() != null && running) {
        publishResync();
      }
      return attempt;
    } catch (RuntimeException ex) {
      listening = false;
      closeConnection();
      return new Connected(null, ex);
    }
  }

  /**
   * Forwards notifications until stopped; the failure of the connection that ended it, if any. A
   * push that cannot be sent is logged and skipped: the connection is fine.
   */
  private Optional<SQLException> pump(Connection current) {
    try {
      PGConnection notifications = current.unwrap(PGConnection.class);
      long nextCheck = System.nanoTime() + VALIDATE_EVERY.toNanos();
      while (running) {
        PGNotification[] batch = notifications.getNotifications(POLL_MILLIS);
        if (batch != null) {
          for (PGNotification notification : batch) {
            push(notification.getParameter())
                .ifPresent(
                    failure ->
                        LOG.warn(
                            "An operation status push could not be sent ({}); skipped",
                            failure.toString()));
          }
        }
        if (System.nanoTime() - nextCheck > 0) {
          if (!current.isValid(VALIDATE_TIMEOUT_SECONDS)) {
            throw new SQLException("The LISTEN connection is no longer valid", "08006");
          }
          nextCheck = System.nanoTime() + VALIDATE_EVERY.toNanos();
        }
      }
      return Optional.empty();
    } catch (SQLException ex) {
      return Optional.of(ex);
    }
  }

  /** Forwards one payload; the failure when it could not be sent. */
  private Optional<RuntimeException> push(String payload) {
    try {
      forward(payload);
      return Optional.empty();
    } catch (RuntimeException notSent) {
      return Optional.of(notSent);
    }
  }

  /**
   * The outcome of opening the dedicated connection and running LISTEN: the connection, or the
   * failure (a {@link SQLException} is logged at WARN by {@link #connect}; the caller retries with
   * backoff).
   */
  private record Connected(Connection connection, Exception failure) {}

  private Connected connect() {
    Properties properties = new Properties();
    properties.setProperty("user", dataSource.determineUsername());
    if (dataSource.determinePassword() != null) {
      properties.setProperty("password", dataSource.determinePassword());
    }
    properties.setProperty("ApplicationName", APPLICATION_NAME);
    Connection opened = null;
    try {
      opened = DriverManager.getConnection(dataSource.determineUrl(), properties);
      try (Statement statement = opened.createStatement()) {
        statement.execute("LISTEN " + PgOperationStatusNotifier.CHANNEL);
      }
      connection = opened;
      listening = true;
      return new Connected(opened, null);
    } catch (SQLException ex) {
      LOG.warn(
          "Cannot LISTEN for operation status changes (SQLSTATE {}); retrying with backoff",
          ex.getSQLState());
      close(opened).ifPresent(ex::addSuppressed);
      return new Connected(null, ex);
    }
  }

  /** Sends one notification's payload, unchanged, to its operation's status topic. */
  private void forward(String payload) {
    Parsed parsed = read(payload);
    Optional<UUID> operationId =
        Optional.ofNullable(parsed.node())
            .flatMap(node -> StompSessions.canonicalUuid(node.path("operationId").asString(null)));
    if (operationId.isEmpty()) {
      LOG.warn(
          "Skipped an operation status notification that cannot be read ({} characters)",
          payload == null ? 0 : payload.length());
      return;
    }
    String correlationId = parsed.node().path("correlationId").asString(null);
    try {
      if (correlationId != null) {
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, LogSafe.clean(correlationId));
      }
      MDC.put(OperationListenerSupport.OPERATION_ID_MDC_KEY, operationId.get().toString());
      messaging.send(StompSessions.operationTopic(operationId.get()), json(payload));
    } finally {
      MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY);
      MDC.remove(OperationListenerSupport.OPERATION_ID_MDC_KEY);
    }
  }

  /** A payload read as JSON: the tree, or why it is not JSON. */
  private record Parsed(JsonNode node, JacksonException failure) {}

  private Parsed read(String payload) {
    if (payload == null) {
      return new Parsed(null, null);
    }
    try {
      return new Parsed(json.readTree(payload), null);
    } catch (JacksonException notJson) {
      return new Parsed(null, notJson);
    }
  }

  private void publishResync() {
    LOG.info("Listening for operation status changes again; publishing a resync");
    messaging.convertAndSend(StompSessions.RESYNC_TOPIC, RESYNC);
  }

  private static Message<byte[]> json(String payload) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
    headers.setLeaveMutable(true);
    return MessageBuilder.createMessage(
        payload.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders());
  }

  private void closeConnection() {
    Connection current = connection;
    connection = null;
    close(current)
        .ifPresent(
            alreadyBroken ->
                LOG.debug(
                    "Closed a broken LISTEN connection (SQLSTATE {})",
                    alreadyBroken.getSQLState()));
  }

  /**
   * Closes a connection that is no longer used; the failure when it was already broken (the driver
   * has released it either way).
   */
  private static Optional<SQLException> close(Connection current) {
    if (current == null) {
      return Optional.empty();
    }
    try {
      current.close();
      return Optional.empty();
    } catch (SQLException alreadyBroken) {
      return Optional.of(alreadyBroken);
    }
  }
}
