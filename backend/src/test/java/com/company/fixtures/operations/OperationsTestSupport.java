package com.company.fixtures.operations;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.operations.application.OperationListenerSupport;
import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Base of the operations ITs: the running application on its own database ({@code seed_operations},
 * main migrations) with the probe operation wired in, a 10 ms retry backoff, the staleness check
 * and the STOMP token check every 200 ms, and every scheduled job off (tests run them by hand).
 * Every subclass shares one context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      TestDatabase.DATABASE_PROPERTY + "=seed_operations",
      "seed.operations.retry-delay=10ms",
      // The staleness monitor checks often, so a stale publication is marked failed in the test.
      "spring.modulith.events.staleness.check-interval=200ms",
      // Expired STOMP sessions are found within a fraction of a second.
      "seed.realtime.token-check-interval=200ms",
      "seed.idempotency.cleanup-cron=-",
      "seed.jobs.operation-cleanup-cron=-",
      "seed.jobs.event-publication-cleanup-cron=-",
      "seed.jobs.event-publication-resubmit-cron=-"
    })
@Import({TestDatabase.class, TestSecurity.class, OperationsTestSupport.Fixtures.class})
public abstract class OperationsTestSupport {

  protected static final Duration WAIT = Duration.ofSeconds(20);

  @Autowired protected OperationService operations;
  @Autowired protected ProbeOperationStarter starter;
  @Autowired protected ProbeOperationBehaviour behaviour;
  @Autowired protected ProbeOperationListener probeListener;
  @Autowired protected JdbcTemplate jdbc;
  @LocalServerPort protected int port;
  @LocalManagementPort protected int managementPort;

  @BeforeEach
  void resetProbe() {
    behaviour.reset(ProbeOperationBehaviour.Mode.SUCCEED);
    probeListener.reset();
  }

  protected RestClient user() {
    return LenientRestClient.forPort(port);
  }

  /** Starts a probe operation as {@code actor} (a {@code user:} or {@code system:} name). */
  protected Operation startProbeAs(String actor) {
    return SystemActor.call(actor, starter::start);
  }

  /**
   * Starts a QUEUED operation of {@code kind} without work behind it, as {@link
   * com.company.fixtures.security.TestJwt#user()} would through a request with {@code
   * correlationId}; the test changes its status itself.
   */
  protected Operation startForTestUser(String kind, String correlationId) {
    MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
    try {
      return SystemActor.call(
          CurrentActor.USER_PREFIX + TestJwt.USER_SUBJECT, () -> operations.start(kind, null));
    } finally {
      MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY);
    }
  }

  /** Waits until the operation has the status, and returns it. */
  protected Operation awaitStatus(UUID id, OperationStatus status) {
    return Awaitility.await()
        .atMost(WAIT)
        .until(() -> operations.find(id).orElseThrow(), found -> found.status() == status);
  }

  /** The {@code common.event_publication} rows of events carrying {@code operationId}. */
  protected java.util.List<Map<String, Object>> publications(UUID operationId) {
    return jdbc.queryForList(
        "SELECT * FROM common.event_publication WHERE serialized_event LIKE ?",
        "%" + operationId + "%");
  }

  /** Waits until the registry holds a publication of the operation's event. */
  protected void awaitPublicationRecorded(Operation operation) {
    Awaitility.await()
        .atMost(WAIT)
        .until(() -> publications(operation.id()), rows -> !rows.isEmpty());
  }

  /** Waits until every publication of the operation's event is completed. */
  protected void awaitPublicationsCompleted(Operation operation) {
    Awaitility.await()
        .atMost(WAIT)
        .until(
            () -> publications(operation.id()),
            rows ->
                !rows.isEmpty()
                    && rows.stream().allMatch(row -> row.get("completion_date") != null));
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Fixtures {

    @Bean
    ScheduledThreadProbe scheduledThreadProbe() {
      return new ScheduledThreadProbe();
    }

    @Bean
    ProbeOperationBehaviour probeOperationBehaviour() {
      return new ProbeOperationBehaviour();
    }

    @Bean
    ProbeOperationListener probeOperationListener(
        OperationListenerSupport support, ProbeOperationBehaviour behaviour) {
      return new ProbeOperationListener(support, behaviour);
    }

    @Bean
    ProbeOperationStarter probeOperationStarter(
        OperationService operations, ApplicationEventPublisher events) {
      return new ProbeOperationStarter(operations, events);
    }
  }
}
