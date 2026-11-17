package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.fixtures.idempotency.IdempotencyProbeController;
import com.company.fixtures.idempotency.IdempotencyProbeFilter;
import com.company.fixtures.idempotency.IdempotencyTestSupport;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.logging.CorrelationIdFilter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The {@code Idempotency-Key} filter against the running application (api-standards.md §1): every
 * row of the story 6c matrix except the cleanup ({@link IdempotencyCleanupIT}) and the {@code
 * migrate} run ({@code MigrationIT}).
 */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyIT extends IdempotencyTestSupport {

  private static final String ITEMS = IdempotencyProbeController.BASE + "/items";
  private static final String SLOW = IdempotencyProbeController.BASE + "/slow";
  private static final String FAIL = IdempotencyProbeController.BASE + "/fail";
  private static final String ASYNC = IdempotencyProbeController.BASE + "/async";
  private static final String FORM = IdempotencyProbeController.BASE + "/form";
  private static final String LARGE = IdempotencyProbeController.BASE + "/large";
  private static final String BODY = "{\"name\":\"first\"}";
  private static final String OTHER_BODY = "{\"name\":\"other\"}";
  private static final Duration ABANDON_AFTER = Duration.ofMinutes(5);

  @Test
  void writeWithoutKeyAndReadWithKeyPassThroughWithoutARow() {
    long before = rowCount();
    String key = unique("get");

    ResponseEntity<byte[]> post = send(HttpMethod.POST, ITEMS, null, BODY, null);
    ResponseEntity<byte[]> get = send(HttpMethod.GET, ITEMS, key, null, null);

    assertThat(post.getStatusCode().value()).isEqualTo(201);
    assertThat(get.getStatusCode().value()).isEqualTo(200);
    assertThat(post.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(probe.invocations()).isEqualTo(2);
    assertThat(rows(key)).isEmpty();
    assertThat(rowCount()).isEqualTo(before);
  }

  @ParameterizedTest(name = "[{0}]")
  @ValueSource(
      strings = {
        "bad key!",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaZ",
        ""
      })
  void malformedKeyIsRejectedBeforeTheHandler(String key, CapturedOutput output) {
    long before = rowCount();

    ResponseEntity<byte[]> response = send(HttpMethod.POST, ITEMS, key, BODY, null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    Map<String, Object> body = parse(response.getBody());
    assertThat(body)
        .containsEntry("errorCode", ErrorCode.MALFORMED_REQUEST)
        .containsEntry("message", "Invalid Idempotency-Key header")
        .containsEntry("path", ITEMS);
    assertThat(probe.invocations()).isZero();
    assertThat(rowCount()).isEqualTo(before);
    assertThat(logMessages(output, IdempotencyFilter.class, "DEBUG"))
        .contains("Rejected a malformed Idempotency-Key header");
  }

  @Test
  void repeatedKeyHeaderIsMalformed() {
    String key = unique("twice");

    ResponseEntity<byte[]> response =
        client()
            .post()
            .uri(ITEMS)
            .header(IdempotencyFilter.HEADER, key, key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(BODY)
            .retrieve()
            .toEntity(byte[].class);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(probe.invocations()).isZero();
    assertThat(rows(key)).isEmpty();
  }

  @Test
  void firstWriteRunsOnceAndStoresItsResponse() {
    String key = unique("k1");

    ResponseEntity<byte[]> response = send(HttpMethod.POST, ITEMS, key, BODY, "first");

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(probe.invocations()).isEqualTo(1);
    assertThat(response.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    Map<String, Object> row = row(key);
    assertThat(row)
        .containsEntry("status", "COMPLETED")
        .containsEntry("response_status", 201)
        .containsEntry(
            "response_content_type", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
        .containsEntry("response_location", response.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertThat((byte[]) row.get("response_body")).isEqualTo(response.getBody());
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(ITEMS + "/1");
  }

  @Test
  void repeatIsReplayedWithoutRunningTheHandler(CapturedOutput output) {
    String key = unique("replay");
    String marker = unique("body-marker");
    String body = "{\"name\":\"" + marker + "\"}";
    ResponseEntity<byte[]> first = send(HttpMethod.POST, ITEMS, key, body, "first");

    ResponseEntity<byte[]> replay = send(HttpMethod.POST, ITEMS, key, body, "second");

    assertThat(probe.invocations()).isEqualTo(1);
    assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(parse(replay.getBody())).containsEntry("correlationId", "first");
    assertThat(replay.getHeaders().getFirst(HttpHeaders.LOCATION))
        .isEqualTo(first.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertThat(replay.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
        .isEqualTo(first.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(replay.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("second");
    awaitInfo(output, key, "replayed");
    assertThat(output.getAll()).doesNotContain(marker);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"PUT, 200", "PATCH, 200", "DELETE, 200"})
  void otherWriteMethodsTakePart(HttpMethod method, int status) {
    String key = unique(method.name());
    String path = ITEMS + "/7";
    String body = method == HttpMethod.DELETE ? null : BODY;

    ResponseEntity<byte[]> first = send(method, path, key, body, null);
    ResponseEntity<byte[]> replay = send(method, path, key, body, null);

    assertThat(first.getStatusCode().value()).isEqualTo(status);
    assertThat(replay.getStatusCode().value()).isEqualTo(status);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @Test
  void sameKeyWithADifferentBodyOrPathIsRejected(CapturedOutput output) {
    String key = unique("reuse");
    send(HttpMethod.POST, ITEMS, key, BODY, null);

    ResponseEntity<byte[]> otherBody = send(HttpMethod.POST, ITEMS, key, OTHER_BODY, null);
    ResponseEntity<byte[]> otherPath = send(HttpMethod.POST, SLOW, key, BODY, null);
    ResponseEntity<byte[]> otherQuery = send(HttpMethod.POST, ITEMS + "?x=1", key, BODY, null);

    for (ResponseEntity<byte[]> response : List.of(otherBody, otherPath, otherQuery)) {
      assertThat(response.getStatusCode().value()).isEqualTo(409);
      assertThat(parse(response.getBody()))
          .containsEntry("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);
      assertThat(response.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    }
    assertThat(probe.invocations()).isEqualTo(1);
    awaitInfo(output, key, "reused");
  }

  @Test
  void duplicateOfARequestInProgressIsAnsweredConflict(CapturedOutput output) throws Exception {
    String key = unique("busy");
    probe.blockNext();
    CompletableFuture<ResponseEntity<byte[]>> first =
        CompletableFuture.supplyAsync(() -> send(HttpMethod.POST, SLOW, key, BODY, null));
    probe.awaitEntered();

    ResponseEntity<byte[]> duplicate = send(HttpMethod.POST, SLOW, key, BODY, null);
    ResponseEntity<byte[]> different = send(HttpMethod.POST, SLOW, key, OTHER_BODY, null);
    probe.release();
    ResponseEntity<byte[]> completed = first.get(30, TimeUnit.SECONDS);

    assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
    assertThat(duplicate.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(parse(duplicate.getBody()))
        .containsEntry("errorCode", ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS)
        .containsEntry(
            "message", "A request with this Idempotency-Key is in progress; retry later");
    assertThat(different.getStatusCode().value()).isEqualTo(409);
    assertThat(parse(different.getBody()))
        .containsEntry("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertThat(completed.getStatusCode().value()).isEqualTo(201);
    assertThat(probe.invocations()).isEqualTo(1);
    assertThat(row(key)).containsEntry("status", "COMPLETED").containsEntry("response_status", 201);
    awaitInfo(output, key, "in progress");
  }

  @Test
  void abandonedClaimIsTakenOverAndTheOriginalCannotOverwriteIt() throws Exception {
    String key = unique("abandoned");
    probe.blockNext();
    CompletableFuture<ResponseEntity<byte[]>> original =
        CompletableFuture.supplyAsync(() -> send(HttpMethod.POST, SLOW, key, BODY, null));
    probe.awaitEntered();
    clock.advance(ABANDON_AFTER.plusSeconds(1));

    ResponseEntity<byte[]> takeover = send(HttpMethod.POST, SLOW, key, BODY, null);
    byte[] storedByTakeover = (byte[]) row(key).get("response_body");
    probe.release();
    ResponseEntity<byte[]> late = original.get(30, TimeUnit.SECONDS);
    ResponseEntity<byte[]> replay = send(HttpMethod.POST, SLOW, key, BODY, null);

    assertThat(takeover.getStatusCode().value()).isEqualTo(201);
    assertThat(storedByTakeover).isEqualTo(takeover.getBody());
    assertThat(late.getStatusCode().value()).isEqualTo(201);
    assertThat(late.getBody()).isNotEqualTo(takeover.getBody());
    assertThat(probe.invocations()).isEqualTo(2);
    Map<String, Object> row = row(key);
    assertThat(row).containsEntry("status", "COMPLETED");
    assertThat((byte[]) row.get("response_body")).isEqualTo(takeover.getBody());
    assertThat(replay.getBody()).isEqualTo(takeover.getBody());
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
  }

  @Test
  void clientErrorIsStoredAndReplayed() {
    String key = unique("invalid");
    String invalid = "{\"name\":\"\"}";

    ResponseEntity<byte[]> first = send(HttpMethod.POST, ITEMS, key, invalid, null);
    ResponseEntity<byte[]> replay = send(HttpMethod.POST, ITEMS, key, invalid, null);

    assertThat(first.getStatusCode().value()).isEqualTo(400);
    assertThat(parse(first.getBody())).containsEntry("errorCode", ErrorCode.VALIDATION_FAILED);
    assertThat(replay.getStatusCode().value()).isEqualTo(400);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {FAIL, IdempotencyProbeFilter.ESCAPE})
  void serverErrorReleasesTheKeySoARetryRunsAgain(String path) {
    String key = unique("fail");

    ResponseEntity<byte[]> first = send(HttpMethod.POST, path, key, BODY, null);
    var rowsAfterFirst = rows(key);
    ResponseEntity<byte[]> retry = send(HttpMethod.POST, path, key, BODY, null);

    assertThat(first.getStatusCode().value()).isEqualTo(500);
    assertThat(parse(first.getBody())).containsEntry("errorCode", ErrorCode.INTERNAL_ERROR);
    assertThat(rowsAfterFirst).isEmpty();
    assertThat(retry.getStatusCode().value()).isEqualTo(500);
    assertThat(retry.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(probe.invocations()).isEqualTo(2);
    assertThat(rows(key)).isEmpty();
  }

  @Test
  void asyncHandlerIsStoredWhenItsResponseIsWritten() {
    String key = unique("async");

    ResponseEntity<byte[]> first = send(HttpMethod.POST, ASYNC, key, BODY, null);
    ResponseEntity<byte[]> replay = send(HttpMethod.POST, ASYNC, key, BODY, null);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(first.getBody()).isNotEmpty();
    assertThat(row(key)).containsEntry("status", "COMPLETED").containsEntry("response_status", 201);
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst(HttpHeaders.LOCATION))
        .isEqualTo(first.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(probe.invocations()).isEqualTo(1);
  }

  static Stream<String> acceptedKeys() {
    String id = UUID.randomUUID().toString();
    return Stream.of(("k" + id + id).substring(0, 64), "dot." + id, "under_" + id, "colon:" + id);
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("acceptedKeys")
  void everyValidKeyIsAccepted(String key) {
    ResponseEntity<byte[]> first = send(HttpMethod.POST, ITEMS, key, BODY, null);
    ResponseEntity<byte[]> replay = send(HttpMethod.POST, ITEMS, key, BODY, null);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @Test
  void sameKeyWithAnotherMethodIsRejected() {
    String key = unique("method");
    String path = ITEMS + "/7";

    ResponseEntity<byte[]> put = send(HttpMethod.PUT, path, key, BODY, null);
    ResponseEntity<byte[]> patch = send(HttpMethod.PATCH, path, key, BODY, null);

    assertThat(put.getStatusCode().value()).isEqualTo(200);
    assertThat(patch.getStatusCode().value()).isEqualTo(409);
    assertThat(parse(patch.getBody())).containsEntry("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @Test
  void errorSentToTheContainerReleasesTheKey() {
    String key = unique("send-error");

    ResponseEntity<byte[]> first =
        send(HttpMethod.POST, IdempotencyProbeFilter.SEND_ERROR, key, BODY, null);
    var rowsAfterFirst = rows(key);
    ResponseEntity<byte[]> retry =
        send(HttpMethod.POST, IdempotencyProbeFilter.SEND_ERROR, key, BODY, null);

    assertThat(first.getStatusCode().value()).isEqualTo(403);
    assertThat(parse(first.getBody())).containsEntry("errorCode", ErrorCode.FORBIDDEN);
    assertThat(rowsAfterFirst).isEmpty();
    assertThat(retry.getStatusCode().value()).isEqualTo(403);
    assertThat(retry.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(probe.invocations()).isEqualTo(2);
  }

  @Test
  void keyedFormPostPassesThroughUntouched() {
    String key = unique("form");

    ResponseEntity<byte[]> response =
        client()
            .post()
            .uri(FORM)
            .header(IdempotencyFilter.HEADER, key)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("name=form-value")
            .retrieve()
            .toEntity(byte[].class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(parse(response.getBody())).containsEntry("data", "form-value");
    assertThat(rows(key)).isEmpty();
  }

  /** The global body limit (RequestSizeFilter, 4 KB here) answers before the key is looked at. */
  @Test
  void requestBodyOverTheLimitIsRejectedBeforeAnyClaim() {
    String key = unique("too-large");
    String body = "{\"name\":\"" + "x".repeat(MAX_BODY_BYTES) + "\"}";

    ResponseEntity<byte[]> response = send(HttpMethod.POST, ITEMS, key, body, null);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    assertThat(parse(response.getBody()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", "Request body exceeds " + MAX_BODY_SIZE);
    assertThat(probe.invocations()).isZero();
    assertThat(rows(key)).isEmpty();
  }

  /** The stored-response cap (1 KB here) is not the request body limit (4 KB here). */
  @Test
  void aRequestBodyOverTheStoredResponseCapIsHandledAndItsSmallResponseStored() {
    String key = unique("mid-size-request");
    String body = "{\"name\":\"" + "x".repeat(2 * MAX_STORED_RESPONSE_BYTES) + "\"}";

    ResponseEntity<byte[]> first = send(HttpMethod.DELETE, ITEMS + "/1", key, body, null);
    ResponseEntity<byte[]> replay = send(HttpMethod.DELETE, ITEMS + "/1", key, body, null);

    assertThat(body.length()).isBetween(MAX_STORED_RESPONSE_BYTES, MAX_BODY_BYTES);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(row(key)).containsEntry("status", "COMPLETED");
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(probe.invocations()).isEqualTo(1);
  }

  /** A small request whose response is over the stored-response cap (1 KB here). */
  @Test
  void aResponseOverTheStoredResponseCapIsSentButNotStored(CapturedOutput output) {
    String key = unique("large-response");

    ResponseEntity<byte[]> first = send(HttpMethod.POST, LARGE, key, null, null);
    var rowsAfterFirst = rows(key);
    ResponseEntity<byte[]> retry = send(HttpMethod.POST, LARGE, key, null, null);

    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(first.getBody())
        .hasSizeGreaterThan(MAX_STORED_RESPONSE_BYTES)
        .hasSizeLessThan(MAX_BODY_BYTES);
    assertThat(rowsAfterFirst).isEmpty();
    assertThat(retry.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(probe.invocations()).isEqualTo(2);
    assertThat(logMessages(output, IdempotencyFilter.class, "WARN"))
        .anyMatch(m -> m.contains(key) && m.contains("not stored"));
  }

  private ResponseEntity<byte[]> send(
      HttpMethod method, String path, String key, String body, String correlationId) {
    var request = client().method(method).uri(path);
    if (key != null) {
      request.header(IdempotencyFilter.HEADER, key);
    }
    if (correlationId != null) {
      request.header(CorrelationIdFilter.HEADER, correlationId);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return request.retrieve().toEntity(byte[].class);
  }

  /** Waits for an INFO line of the filter naming {@code key} and containing {@code text}. */
  private void awaitInfo(CapturedOutput output, String key, String text) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> logMessages(output, IdempotencyFilter.class, "INFO"),
            found -> found.stream().anyMatch(m -> m.contains(key) && m.contains(text)));
  }
}
