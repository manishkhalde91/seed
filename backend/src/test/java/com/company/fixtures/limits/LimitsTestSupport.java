package com.company.fixtures.limits;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.time.MutableClock;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.idempotency.IdempotencyFilter;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.security.Roles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the request-limit ITs: the running application with {@link LimitsProbeController} and a
 * {@link MutableClock} (reset before each test), on the shared database. Subclasses choose the
 * limits with {@code @TestPropertySource(properties = ...)}. Helpers send JSON bodies of an exact
 * size (with a {@code Content-Length}, or chunked without one), multipart uploads and writes as a
 * caller of their own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class, LimitsTestSupport.Fixtures.class})
public abstract class LimitsTestSupport {

  /** The instant the clock is reset to before each test. */
  public static final Instant START = Instant.parse("2026-11-16T09:00:00Z");

  /** Wires the probe endpoints and the mutable clock. */
  @TestConfiguration(proxyBeanMethods = false)
  @Import(LimitsProbeController.class)
  public static class Fixtures {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(START);
    }
  }

  private static final String TEXT_PREFIX = "{\"text\":\"";
  private static final String TEXT_SUFFIX = "\"}";

  @Autowired protected MutableClock clock;
  @Autowired protected LimitsProbeController probe;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired private JsonMapper jsonMapper;
  @LocalServerPort private int port;

  @BeforeEach
  void resetFixtures() {
    clock.set(START);
    probe.reset();
  }

  /** A token for a caller of this test alone, so budgets never leak between tests. */
  protected static String newCaller() {
    return TestJwt.builder().subject("limits-" + UUID.randomUUID()).roles(Roles.USER).sign();
  }

  protected static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /** A JSON document {@code {"text":"xxx…"}} of exactly {@code bytes} bytes. */
  protected static byte[] jsonOfSize(int bytes) {
    int fill = bytes - TEXT_PREFIX.length() - TEXT_SUFFIX.length();
    byte[] body = new byte[bytes];
    byte[] prefix = TEXT_PREFIX.getBytes(StandardCharsets.UTF_8);
    byte[] suffix = TEXT_SUFFIX.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(prefix, 0, body, 0, prefix.length);
    Arrays.fill(body, prefix.length, prefix.length + fill, (byte) 'x');
    System.arraycopy(suffix, 0, body, prefix.length + fill, suffix.length);
    return body;
  }

  /** {@code token} as a client; {@code null} sends none. */
  protected RestClient client(String token) {
    return token == null
        ? LenientRestClient.anonymous(port)
        : LenientRestClient.forPort(port, token);
  }

  /** Posts {@code body} as JSON with its {@code Content-Length}. */
  protected ResponseEntity<byte[]> postJson(String token, byte[] body, String correlationId) {
    var request =
        client(token)
            .post()
            .uri(LimitsProbeController.BODY)
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(body.length);
    if (correlationId != null) {
      request.header(CorrelationIdFilter.HEADER, correlationId);
    }
    return request.body(body).retrieve().toEntity(byte[].class);
  }

  /** A response of {@link #postChunked}: status, headers and body. */
  protected record Answer(int status, Map<String, List<String>> headers, byte[] body) {

    public String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(entry -> entry.getValue().getFirst())
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * Posts {@code body} as JSON without a {@code Content-Length} (HTTP/1.1 chunked), with {@code
   * idempotencyKey} unless {@code null}.
   */
  protected Answer postChunked(String token, byte[] body, String idempotencyKey) {
    return post(
        token,
        LimitsProbeController.BODY,
        MediaType.APPLICATION_JSON_VALUE,
        body,
        idempotencyKey,
        true);
  }

  /**
   * Posts {@code body} as {@code contentType} to {@code path}: chunked (no {@code Content-Length})
   * or with its length, with {@code idempotencyKey} unless {@code null}.
   */
  protected Answer post(
      String token,
      String path,
      String contentType,
      byte[] body,
      String idempotencyKey,
      boolean chunked) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token))
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .POST(
                chunked
                    ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (idempotencyKey != null) {
      request.header(IdempotencyFilter.HEADER, idempotencyKey);
    }
    try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      HttpResponse<byte[]> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Answer(response.statusCode(), response.headers().map(), response.body());
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  /** Uploads a file of {@code bytes} bytes as {@code multipart/form-data}. */
  protected ResponseEntity<byte[]> upload(String token, int bytes) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "file",
        new ByteArrayResource(new byte[bytes]) {
          @Override
          public String getFilename() {
            return "upload.bin";
          }
        });
    return client(token)
        .post()
        .uri(LimitsProbeController.UPLOAD)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts)
        .retrieve()
        .toEntity(byte[].class);
  }

  /**
   * One write ({@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}) or read as {@code
   * token}.
   */
  protected ResponseEntity<byte[]> send(HttpMethod method, String token) {
    return client(token)
        .method(method)
        .uri(LimitsProbeController.WRITES)
        .retrieve()
        .toEntity(byte[].class);
  }

  /** A keyed {@code POST} as {@code token}. */
  protected ResponseEntity<byte[]> keyedPost(String token, String key) {
    return client(token)
        .post()
        .uri(LimitsProbeController.WRITES)
        .header(IdempotencyFilter.HEADER, key)
        .retrieve()
        .toEntity(byte[].class);
  }

  protected Map<String, Object> parse(byte[] json) {
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  protected long idempotencyRows(String key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM common.idempotency_record WHERE idempotency_key = ?",
        Long.class,
        key);
  }

  /** The captured log lines of {@code logger} at {@code level}. */
  protected static List<EcsLogLines.Line> logLines(
      CapturedOutput output, Class<?> logger, String level) {
    return EcsLogLines.of(output.getOut(), logger.getName()).stream()
        .filter(line -> line.level().equals(level))
        .toList();
  }
}
