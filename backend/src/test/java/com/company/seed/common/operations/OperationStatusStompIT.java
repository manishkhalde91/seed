package com.company.seed.common.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.SecondInstance;
import com.company.fixtures.operations.StompTestClient;
import com.company.fixtures.operations.StompTestClient.Frame;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.operations.application.OperationStatusPayload;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.infrastructure.ws.StompSessionRegistry;
import com.company.seed.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live operation status over STOMP (api-standards.md §9, realtime-and-storage.md §1), one test
 * per row of the story's I/O matrix except fan-out ({@code OperationStatusFanOutStompIT}) and the
 * listener reconnect ({@code PgStatusListenerIT}). Clients are raw STOMP clients, so every frame
 * and close status is the one a real client would see.
 */
@ExtendWith(OutputCaptureExtension.class)
class OperationStatusStompIT extends OperationsTestSupport {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
  private static final Pattern UUID_V7 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final String CONNECT_LOGGER =
      "com.company.seed.security.ws.StompAuthorizationInterceptor";

  @Autowired private JsonMapper json;
  @Autowired private MeterRegistry meters;
  @Autowired private ApplicationContext context;

  // --- Connect ---------------------------------------------------------------------------------

  @Test
  void aValidUserTokenIsConnectedWithTheHeartBeat() {
    try (StompTestClient client = StompTestClient.open(port)) {
      client.sendConnect(
          Map.of("Authorization", TestJwt.bearer(TestJwt.user()), "heart-beat", "10000,10000"));

      Frame connected = client.next();

      assertThat(connected.command()).isEqualTo("CONNECTED");
      assertThat(connected.header("version")).isEqualTo("1.2");
      assertThat(connected.header("heart-beat")).isEqualTo("10000,10000");
      assertThat(client.isOpen()).isTrue();
    }
  }

  static List<Arguments> refusedConnects() {
    return List.of(
        Arguments.of("no token", Map.of()),
        Arguments.of("a malformed token", Map.of("Authorization", "Bearer not-a-jwt")),
        Arguments.of(
            "a token for another audience",
            Map.of(
                "Authorization",
                TestJwt.bearer(TestJwt.builder().roles(Roles.USER).audience("other-api").sign()))),
        Arguments.of(
            "an expired token",
            Map.of(
                "Authorization",
                TestJwt.bearer(
                    TestJwt.builder()
                        .roles(Roles.USER)
                        .expiresAt(Instant.now().minus(Duration.ofMinutes(5)))
                        .sign()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedConnects")
  void aConnectWithoutAValidTokenIsUnauthorizedAndClosed(
      String description, Map<String, String> headers) {
    try (StompTestClient client = StompTestClient.open(port)) {
      client.sendConnect(headers);

      assertThat(client.awaitErrorAndClose()).isEqualTo("unauthorized");
    }
  }

  @Test
  void aFrameBeforeConnectIsUnauthorizedAndClosed() {
    try (StompTestClient client = StompTestClient.open(port)) {
      client.subscribe("s", "/topic/system/resync");

      assertThat(client.awaitErrorAndClose()).isEqualTo("unauthorized");
    }
  }

  @Test
  void aSecondConnectIsUnauthorizedAndClosed() {
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.sendConnect(Map.of("Authorization", TestJwt.bearer(TestJwt.admin())));

      assertThat(client.awaitErrorAndClose()).isEqualTo("unauthorized");
    }
  }

  // --- Origin ----------------------------------------------------------------------------------

  @Test
  void theHandshakeAcceptsAnAllowedOriginAndNoOrigin() {
    WebSocketHttpHeaders allowed = new WebSocketHttpHeaders();
    allowed.setOrigin("http://localhost:4200");
    try (StompTestClient fromBrowser = StompTestClient.open(port, allowed);
        StompTestClient noOrigin = StompTestClient.open(port)) {
      for (StompTestClient client : List.of(fromBrowser, noOrigin)) {
        client.sendConnect(Map.of("Authorization", TestJwt.bearer(TestJwt.user())));
        assertThat(client.next().command()).isEqualTo("CONNECTED");
      }
    }
  }

  @Test
  void theHandshakeRefusesAnOriginNotAllowedWithoutUpgrading() {
    WebSocketHttpHeaders foreign = new WebSocketHttpHeaders();
    foreign.setOrigin("https://evil.example");

    assertThatThrownBy(() -> StompTestClient.open(port, foreign)).hasStackTraceContaining("403");
  }

  // --- Live push and snapshot --------------------------------------------------------------------

  @Test
  void theRequesterReceivesEveryStatusChangeInVersionOrderWithTheStartingCorrelationId() {
    Operation started = startAsUser("live-push-" + UUID.randomUUID());
    String topic = "/topic/operations/" + started.id();
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.subscribe("live", topic);
      StompTestClient.awaitSubscribed(context, topic);

      Operation running = operations.markRunning(started.id(), 1);
      Operation succeeded = operations.complete(started.id(), 2, Map.of("rowCount", 3));

      Frame first = client.awaitMessage(topic);
      Frame second = client.awaitMessage(topic);
      assertThat(List.of(first, second))
          .allSatisfy(
              push -> {
                assertThat(push.header("subscription")).isEqualTo("live");
                assertThat(push.header("content-type")).startsWith("application/json");
              });
      assertThat(read(first.body())).isEqualTo(expected(running)).containsEntry("version", 2);
      assertThat(read(second.body()))
          .isEqualTo(expected(succeeded))
          .containsEntry("version", 3)
          .containsEntry("status", "SUCCEEDED")
          .containsEntry("correlationId", started.correlationId());
    }
  }

  @Test
  void anAdminReceivesThePushesOfAnotherUsersOperation() {
    Operation started = startAsUser("admin-push-" + UUID.randomUUID());
    String topic = "/topic/operations/" + started.id();
    try (StompTestClient admin = StompTestClient.connect(port, TestJwt.admin())) {
      admin.subscribe("watch", topic);
      StompTestClient.awaitSubscribed(context, topic);

      Operation running = operations.markRunning(started.id(), 1);

      assertThat(read(admin.awaitMessage(topic).body())).isEqualTo(expected(running));
    }
  }

  @Test
  void theSnapshotAnswersTheRequesterAndAnAdminOnceWithTheCurrentPayload() {
    Operation started = startAsUser("snapshot-" + UUID.randomUUID());
    Operation running = operations.markRunning(started.id(), 1);
    String snapshot = "/app/operations/" + started.id();

    for (String token : List.of(TestJwt.user(), TestJwt.admin())) {
      try (StompTestClient client = StompTestClient.connect(port, token)) {
        client.subscribe("snap", snapshot);

        Frame message = client.next();

        assertThat(message.command()).isEqualTo("MESSAGE");
        assertThat(message.header("destination")).isEqualTo(snapshot);
        assertThat(message.header("subscription")).isEqualTo("snap");
        assertThat(read(message.body())).isEqualTo(expected(running));
        assertThat(client.poll(Duration.ofMillis(500))).as("only one snapshot").isNull();
      }
    }
  }

  // --- Subscription denied -----------------------------------------------------------------------

  @Test
  void aSubscriptionOutsideTheCallersOperationsIsRefusedAndClosed() {
    Operation started = startAsUser("denied-" + UUID.randomUUID());
    String otherUser = TestJwt.builder().subject("other-user").roles(Roles.USER).sign();
    Map<String, String> otherUserCases =
        Map.of(
            "/topic/operations/" + started.id(), "forbidden-destination",
            "/app/operations/" + started.id(), "forbidden-destination");
    Map<String, String> requesterCases =
        Map.of(
            "/topic/operations/" + UUID.randomUUID(),
            "not-found",
            "/app/operations/" + UUID.randomUUID(),
            "not-found",
            "/topic/operations/not-a-uuid",
            "not-found",
            "/topic/operations/" + started.id().toString().toUpperCase(),
            "not-found",
            "/topic/operations/*",
            "not-found",
            "/topic/other",
            "forbidden-destination",
            "/topic/**",
            "forbidden-destination",
            "/user/queue/errors",
            "forbidden-destination");

    otherUserCases.forEach((destination, code) -> assertRefused(otherUser, destination, code));
    requesterCases.forEach((destination, code) -> assertRefused(TestJwt.user(), destination, code));
  }

  private void assertRefused(String token, String destination, String code) {
    try (StompTestClient client = StompTestClient.connect(port, token)) {
      client.subscribe("denied", destination);

      assertThat(client.awaitErrorAndClose()).as(destination).isEqualTo(code);
    }
  }

  // --- Limits ----------------------------------------------------------------------------------

  @Test
  void theFiftyFirstSubscriptionIsRefused() {
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      for (int i = 0; i < 50; i++) {
        client.subscribe("s" + i, "/topic/system/resync");
      }
      assertAlive(client);

      client.subscribe("s50", "/topic/system/resync");

      assertThat(client.awaitErrorAndClose()).isEqualTo("too-many-subscriptions");
    }
  }

  @Test
  void anUnsubscribeFreesASlot() {
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      for (int i = 0; i < 50; i++) {
        client.subscribe("s" + i, "/topic/system/resync");
      }

      client.unsubscribe("s0");
      client.subscribe("s50", "/topic/system/resync");
      assertAlive(client);

      client.subscribe("s51", "/topic/system/resync");
      assertThat(client.awaitErrorAndClose()).isEqualTo("too-many-subscriptions");
    }
  }

  @Test
  void aFrameOfFifteenKilobytesPassesAndOneOfSeventeenClosesTheSession() {
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.send(
          "SUBSCRIBE",
          Map.of(
              "id", "padded", "destination", "/topic/system/resync", "x-pad", "p".repeat(15_000)),
          "");
      assertAlive(client);

      client.send(
          "SUBSCRIBE",
          Map.of("id", "big", "destination", "/topic/system/resync", "x-pad", "p".repeat(17_408)),
          "");

      // The WebSocket message is over the session's limit: closed with 1009 (message too big).
      assertThat(client.awaitClosed().getCode())
          .isEqualTo(CloseStatus.TOO_BIG_TO_PROCESS.getCode());
    }
  }

  // --- Push-only -------------------------------------------------------------------------------

  @Test
  void aSendIsRefusedAndClosed() {
    try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
      client.send(
          "SEND", Map.of("destination", "/app/anything", "content-type", "text/plain"), "hello");

      assertThat(client.awaitErrorAndClose()).isEqualTo("forbidden-destination");
    }
  }

  // --- Token expiry ----------------------------------------------------------------------------

  @Test
  void aSessionIsClosedWithTokenExpiredOnceItsTokenExpires() {
    Instant expiresAt = Instant.now().plusSeconds(2);
    String token =
        TestJwt.builder().subject("expiring-user").roles(Roles.USER).expiresAt(expiresAt).sign();
    try (StompTestClient client = StompTestClient.connect(port, token)) {

      assertThat(client.awaitErrorAndClose()).isEqualTo("token-expired");
      // JWT exp has whole seconds: the token expired at or before expiresAt. The check runs
      // every 200 ms, so the session is closed within a few checks of it.
      assertThat(Instant.now())
          .isAfterOrEqualTo(expiresAt.minusSeconds(1))
          .isBefore(expiresAt.plus(Duration.ofMillis(1500)));
    }
  }

  /** The decoder accepts a token up to 30 s past {@code exp} (clock skew); CONNECT does not. */
  @Test
  void aConnectWithAnAlreadyExpiredTokenIsTokenExpired() {
    String token =
        TestJwt.builder().roles(Roles.USER).expiresAt(Instant.now().minusSeconds(5)).sign();
    try (StompTestClient client = StompTestClient.open(port)) {
      client.sendConnect(Map.of("Authorization", TestJwt.bearer(token)));

      assertThat(client.awaitErrorAndClose()).isEqualTo("token-expired");
    }
  }

  // --- Correlation -----------------------------------------------------------------------------

  @Test
  void theSessionTakesAValidCorrelationIdFromConnect(CapturedOutput output) {
    String subject = "corr-" + UUID.randomUUID();
    String correlationId = "ws-abc-" + UUID.randomUUID();

    connectAndDisconnect(subject, correlationId);

    EcsLogLines.Line connected = awaitConnectLine(output, subject);
    assertThat(connected.field(ApiResponses.CORRELATION_ID_MDC_KEY)).isEqualTo(correlationId);
  }

  @Test
  void anInvalidCorrelationIdIsReplacedByANewUuidV7(CapturedOutput output) {
    String subject = "corr-" + UUID.randomUUID();

    connectAndDisconnect(subject, "not valid!");

    EcsLogLines.Line connected = awaitConnectLine(output, subject);
    assertThat(connected.field(ApiResponses.CORRELATION_ID_MDC_KEY)).matches(UUID_V7);
  }

  private void connectAndDisconnect(String subject, String correlationId) {
    String token = TestJwt.builder().subject(subject).roles(Roles.USER).sign();
    try (StompTestClient client = StompTestClient.open(port)) {
      client.sendConnect(
          Map.of("Authorization", TestJwt.bearer(token), "X-Correlation-Id", correlationId));
      assertThat(client.next().command()).isEqualTo("CONNECTED");
    }
  }

  private static EcsLogLines.Line awaitConnectLine(CapturedOutput output, String subject) {
    return Awaitility.await()
        .atMost(WAIT)
        .until(
            () ->
                EcsLogLines.of(output.getAll(), CONNECT_LOGGER).stream()
                    .filter(line -> subject.equals(line.field("user.id")))
                    .filter(line -> line.message().contains("connected"))
                    .findFirst()
                    .orElse(null),
            line -> line != null);
  }

  // --- Shutdown --------------------------------------------------------------------------------

  /**
   * Uses the JDK's WebSocket client: Tomcat's client reports a received 1012 as 1002, the JDK's
   * passes the status code on as it arrived.
   */
  @Test
  void anOpenSessionIsClosedWith1012WhenTheApplicationStops() throws Exception {
    ConfigurableApplicationContext instance = SecondInstance.start();
    try {
      CompletableFuture<Integer> closeCode = new CompletableFuture<>();
      BlockingQueue<String> frames = new LinkedBlockingQueue<>();
      WebSocket socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .subprotocols("v12.stomp")
              .buildAsync(
                  URI.create("ws://localhost:" + SecondInstance.port(instance) + "/ws"),
                  new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      text.append(data);
                      if (last) {
                        frames.add(text.toString());
                        text.setLength(0);
                      }
                      webSocket.request(1);
                      return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(
                        WebSocket webSocket, int statusCode, String reason) {
                      closeCode.complete(statusCode);
                      return null;
                    }
                  })
              .get(WAIT.toSeconds(), TimeUnit.SECONDS);
      socket
          .sendText(
              "CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\nAuthorization:"
                  + TestJwt.bearer(TestJwt.user())
                  + "\n\n\0",
              true)
          .get(WAIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(frames.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).startsWith("CONNECTED");

      instance.close();

      assertThat(closeCode.get(WAIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(CloseStatus.SERVICE_RESTARTED.getCode());
    } finally {
      instance.close();
    }
  }

  // --- Metric ----------------------------------------------------------------------------------

  @Test
  void theSessionsGaugeCountsConnectedSessions() {
    Awaitility.await().atMost(WAIT).until(() -> sessionsGauge() == 0);

    try (StompTestClient first = StompTestClient.connect(port, TestJwt.user())) {
      try (StompTestClient second = StompTestClient.connect(port, TestJwt.user())) {
        Awaitility.await().atMost(WAIT).until(() -> sessionsGauge() == 2);
        assertThat(prometheus()).containsPattern("(?m)^seed_ws_sessions_active(\\{[^}]*})? 2\\.0$");
      }
      Awaitility.await().atMost(WAIT).until(() -> sessionsGauge() == 1);
      assertThat(first.isOpen()).isTrue();
    }
  }

  private double sessionsGauge() {
    return meters.get(StompSessionRegistry.METRIC_NAME).gauge().value();
  }

  private String prometheus() {
    return org.springframework.web.client.RestClient.create("http://localhost:" + managementPort)
        .get()
        .uri("/actuator/prometheus")
        .retrieve()
        .body(String.class);
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private Operation startAsUser(String correlationId) {
    return startForTestUser("STOMP_PROBE", correlationId);
  }

  /** The session still handles frames: a snapshot of a fresh operation arrives. */
  private void assertAlive(StompTestClient client) {
    Operation probe = startAsUser("alive-" + UUID.randomUUID());
    String snapshot = "/app/operations/" + probe.id();
    client.subscribe("alive-" + probe.id(), snapshot);
    assertThat(client.awaitMessage(snapshot)).isNotNull();
  }

  private Map<String, Object> read(String body) {
    return json.readValue(body, JSON_OBJECT);
  }

  /** The §9 payload of {@code operation}, as the notifier writes it. */
  private Map<String, Object> expected(Operation operation) {
    return read(json.writeValueAsString(OperationStatusPayload.of(operation)));
  }
}
