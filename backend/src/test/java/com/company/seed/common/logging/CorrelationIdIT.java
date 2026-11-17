package com.company.seed.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.ProbeController;
import com.company.fixtures.web.ProbeFilter;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.security.Roles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correlation ID, request summary line and structured (ECS) log output against the running
 * application, with the console output captured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  TestDatabase.class,
  TestSecurity.class,
  ProbeController.class,
  CorrelationIdIT.ProbeFilterConfig.class
})
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdIT {

  private static final Pattern UUID_V7 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final String SUMMARY_LOGGER = RequestLoggingFilter.class.getName();
  private static final Duration LOG_WAIT = Duration.ofSeconds(5);

  @TestConfiguration
  static class ProbeFilterConfig {
    @Bean
    ProbeFilter probeFilter(ApiErrorResponseWriter writer) {
      return new ProbeFilter(writer);
    }
  }

  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;
  @Autowired private JsonMapper jsonMapper;

  private RestClient client;

  @BeforeEach
  void createClient() {
    client = LenientRestClient.forPort(port);
  }

  @Test
  void suppliedIdIsEchoedInHeaderEnvelopeAndRequestLine(CapturedOutput output) {
    ResponseEntity<String> response = get("/probe/ok", "abc");

    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("abc");
    assertThat(parse(response.getBody())).containsEntry("correlationId", "abc");
    Map<String, Object> line = awaitSingleSummary(output, "abc");
    assertThat(line).containsEntry("correlationId", "abc");
  }

  @ParameterizedTest(name = "[{0}]")
  @ValueSource(
      strings = {
        "",
        "bad id!",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaZ",
        "abcé"
      })
  void missingOrInvalidIdIsReplacedByANewUuidV7(String supplied, CapturedOutput output) {
    ResponseEntity<String> response = get("/probe/ok", supplied.isEmpty() ? null : supplied);

    String header = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
    assertThat(header).matches(UUID_V7);
    assertThat(parse(response.getBody())).containsEntry("correlationId", header);
    awaitSingleSummary(output, header);
    if (!supplied.isEmpty()) {
      assertThat(output.getAll()).doesNotContain(supplied);
    }
  }

  @Test
  void idOf64AllowedCharactersIsKept() {
    String supplied = "Az09._:-".repeat(8);

    ResponseEntity<String> response = get("/probe/ok", supplied);

    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(supplied);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "/probe/throw/boom,        500",
    "/probe/throw/conflict,    409",
    "/probe-filter/throws,     500",
    "/probe-filter/send-error, 403",
    "/probe-filter/seed,       409",
    "/probe-filter/writer,     429",
    "/probe/async/conflict,    409",
    "/no/such/route,           404"
  })
  void errorEnvelopeCarriesTheResponseHeaderId(String path, int status) {
    for (String supplied : new String[] {"err-" + UUID.randomUUID(), null}) {
      ResponseEntity<String> response = get(path, supplied);

      assertThat(response.getStatusCode().value()).isEqualTo(status);
      String header = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
      assertThat(header).isNotBlank();
      if (supplied != null) {
        assertThat(header).isEqualTo(supplied);
      }
      assertThat(parse(response.getBody())).containsEntry("correlationId", header);
    }
  }

  @Test
  void structuredMembersAndMdcEntriesNamedLikeSecretsAreMasked(CapturedOutput output) {
    Logger log = LoggerFactory.getLogger(CorrelationIdIT.class);
    MDC.put("token", "t0k");
    try {
      log.atInfo()
          .addKeyValue("password", "hunter2")
          .addKeyValue("ApiKey", "k3y")
          .addKeyValue("user.email", "someone@example.com")
          .addKeyValue("accessToken", "acc3ss")
          .addKeyValue("refresh_token", "r3fresh")
          .addKeyValue("client_secret", "cl1ent")
          .addKeyValue("x-api-key", "x4pi")
          .addKeyValue("userEmail", "other@example.com")
          .addKeyValue("tokenCount", 3)
          .addKeyValue("note", "visible")
          .log("masking probe");
    } finally {
      MDC.remove("token");
    }

    Map<String, Object> line =
        awaitLines(output, l -> "masking probe".equals(l.get("message"))).getFirst();
    assertThat(line)
        .containsEntry("password", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("token", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("ApiKey", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("accessToken", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("refresh_token", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("client_secret", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("x-api-key", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("userEmail", SensitiveMemberMaskingCustomizer.MASK)
        .containsEntry("tokenCount", 3)
        .containsEntry("note", "visible");
    assertThat(field(line, "user.email")).isEqualTo(SensitiveMemberMaskingCustomizer.MASK);
    assertThat(output.getAll())
        .doesNotContain("hunter2")
        .doesNotContain("t0k")
        .doesNotContain("k3y")
        .doesNotContain("someone@example.com")
        .doesNotContain("acc3ss")
        .doesNotContain("r3fresh")
        .doesNotContain("cl1ent")
        .doesNotContain("x4pi")
        .doesNotContain("other@example.com");
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"password\":\"hunter2\"}                | 400",
        "{\"name\":\"ok\",\"password\":\"hunter2\"} | 201",
        "{\"name\":\"\",\"secret\":\"hunter2\"}     | 400"
      })
  void bearerTokenAndPasswordNeverReachTheLog(String body, int status, CapturedOutput output) {
    String id = "leak-" + UUID.randomUUID();
    String jwt = TestJwt.builder().subject("user-1").roles(Roles.USER).sign();
    ResponseEntity<String> response =
        client
            .post()
            .uri("/probe/items")
            .header(CorrelationIdFilter.HEADER, id)
            .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(jwt))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    awaitSingleSummary(output, id);
    assertThat(output.getAll()).doesNotContain(jwt).doesNotContain("hunter2");
  }

  @Test
  void summaryLineCarriesMethodRouteTemplateStatusAndDuration(CapturedOutput output) {
    String id = "sum-" + UUID.randomUUID();
    get("/probe/items/7?debug=query-value", id);

    Map<String, Object> line = awaitSingleSummary(output, id);
    assertThat(field(line, "log.level")).isEqualTo("INFO");
    assertThat((String) line.get("message"))
        .matches("GET /probe/items/\\{id} -> 200 in \\d+ ms")
        .doesNotContain("query-value");
    assertThat(field(line, "http.request.method")).isEqualTo("GET");
    assertThat(field(line, "http.route")).isEqualTo("/probe/items/{id}");
    assertThat(field(line, "http.response.status_code")).isEqualTo(200);
    assertThat(field(line, "event.duration"))
        .isInstanceOf(Number.class)
        .satisfies(nanos -> assertThat(((Number) nanos).longValue()).isPositive());
  }

  @Test
  void unknownRouteIsLoggedAsUnmatched(CapturedOutput output) {
    String id = "unm-" + UUID.randomUUID();
    get("/no/such/route", id);

    assertThat((String) awaitSingleSummary(output, id).get("message"))
        .matches("GET UNMATCHED -> 404 in \\d+ ms");
  }

  @Test
  void filterExceptionIsLoggedOnceWithTheFinalStatusAndNeverAsError(CapturedOutput output) {
    String id = "flt-" + UUID.randomUUID();
    get("/probe-filter/throws", id);

    String message = (String) awaitSingleSummary(output, id).get("message");
    assertThat(message).matches("GET UNMATCHED -> 500 in \\d+ ms").doesNotContain("/error");
  }

  @Test
  void everyErrorLineOfAFilterExceptionCarriesTheCorrelationId(CapturedOutput output) {
    String id = "flterr-" + UUID.randomUUID();
    get("/probe-filter/throws", id);

    // The container's own "Servlet.service() ... threw exception" line and JsonErrorController's.
    List<Map<String, Object>> errors =
        await()
            .atMost(LOG_WAIT)
            .until(
                () -> lines(output, line -> "ERROR".equals(field(line, "log.level"))),
                found -> found.size() >= 2);
    assertThat(errors).allSatisfy(line -> assertThat(line).containsEntry("correlationId", id));
  }

  @Test
  void sendErrorIsLoggedOnceWithTheErrorStatus(CapturedOutput output) {
    String id = "snd-" + UUID.randomUUID();
    get("/probe-filter/send-error", id);

    assertThat((String) awaitSingleSummary(output, id).get("message"))
        .matches("GET UNMATCHED -> 403 in \\d+ ms");
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"ok, 201", "conflict, 409"})
  void asyncRequestIsLoggedOnceWithTheFinalStatus(
      String outcome, int status, CapturedOutput output) {
    String id = "async-" + UUID.randomUUID();
    ResponseEntity<String> response = get("/probe/async/" + outcome, id);

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    // The success envelope is built on the executor thread: the async TaskDecorator carries the
    // MDC.
    assertThat(parse(response.getBody())).containsEntry("correlationId", id);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(id);
    assertThat((String) awaitSingleSummary(output, id).get("message"))
        .matches("GET /probe/async/\\{outcome} -> " + status + " in \\d+ ms");
  }

  @Test
  void actuatorRequestsAreNotSummarised(CapturedOutput output) {
    String id = "act-" + UUID.randomUUID();
    String marker = "after-" + UUID.randomUUID();
    RestClient management =
        RestClient.builder()
            .baseUrl("http://localhost:" + managementPort)
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build();
    ResponseEntity<String> health =
        management
            .get()
            .uri("/actuator/health")
            .header(CorrelationIdFilter.HEADER, id)
            .retrieve()
            .toEntity(String.class);
    get("/actuator/health", id);
    get("/probe/ok", marker);

    assertThat(health.getStatusCode().value()).isEqualTo(200);
    awaitSingleSummary(output, marker);
    assertThat(summaries(output, id)).isEmpty();
  }

  @Test
  void unexpected500IsAnEcsErrorLineWithCorrelationIdAndServiceName(CapturedOutput output) {
    String id = "boom-" + UUID.randomUUID();
    ResponseEntity<String> response = get("/probe/throw/boom", id);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    Map<String, Object> error =
        awaitLines(
                output,
                line ->
                    id.equals(line.get("correlationId"))
                        && "ERROR".equals(field(line, "log.level")))
            .getFirst();
    assertThat(field(error, "service.name")).isEqualTo("seed");
    assertThat(error).containsEntry(ApiResponses.CORRELATION_ID_MDC_KEY, id);
  }

  private ResponseEntity<String> get(String path, String correlationId) {
    RestClient.RequestHeadersSpec<?> request = client.method(HttpMethod.GET).uri(path);
    if (correlationId != null) {
      request.header(CorrelationIdFilter.HEADER, correlationId);
    }
    return request.retrieve().toEntity(String.class);
  }

  private Map<String, Object> parse(String json) {
    assertThat(json).isNotBlank();
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  /** Waits for the request's summary line, then checks it is the only one. */
  private Map<String, Object> awaitSingleSummary(CapturedOutput output, String correlationId) {
    List<Map<String, Object>> lines =
        await()
            .atMost(LOG_WAIT)
            .until(() -> summaries(output, correlationId), found -> !found.isEmpty());
    assertThat(lines).hasSize(1);
    return lines.getFirst();
  }

  private List<Map<String, Object>> summaries(CapturedOutput output, String correlationId) {
    return lines(
        output,
        line ->
            correlationId.equals(line.get("correlationId"))
                && SUMMARY_LOGGER.equals(field(line, "log.logger")));
  }

  private List<Map<String, Object>> awaitLines(
      CapturedOutput output, Predicate<Map<String, Object>> filter) {
    return await().atMost(LOG_WAIT).until(() -> lines(output, filter), found -> !found.isEmpty());
  }

  /** Every captured JSON log line matching the filter. */
  private List<Map<String, Object>> lines(
      CapturedOutput output, Predicate<Map<String, Object>> filter) {
    List<Map<String, Object>> matching = new ArrayList<>();
    for (String raw : output.getOut().split("\\R")) {
      if (raw.startsWith("{")) {
        Map<String, Object> line = parse(raw);
        if (filter.test(line)) {
          matching.add(line);
        }
      }
    }
    return matching;
  }

  /** A member by dotted path, whether ECS wrote it flat ({@code "log.level"}) or nested. */
  private static Object field(Map<String, Object> line, String path) {
    if (line.containsKey(path)) {
      return line.get(path);
    }
    Object current = line;
    for (String segment : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(segment);
    }
    return current;
  }
}
