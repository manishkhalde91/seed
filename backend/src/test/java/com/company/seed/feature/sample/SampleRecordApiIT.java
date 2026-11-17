package com.company.seed.feature.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.TestJwt;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.error.web.GlobalExceptionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DEMO ONLY. Every row of the story 8 matrix over HTTP, with {@link TestJwt} tokens: create, the
 * validation and duplicate errors, read, update (optimistic locking included), paging and sorting,
 * soft delete and restore, and the role checks.
 */
class SampleRecordApiIT extends SampleTestSupport {

  private static final String BASE = "/api/v1/sample-records";
  private static final String USER = TestJwt.user();
  private static final String ADMIN = TestJwt.admin();
  private static final String USER_ACTOR = "user:" + TestJwt.USER_SUBJECT;
  private static final String ADMIN_ACTOR = "user:" + TestJwt.ADMIN_SUBJECT;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  // --- Create, validation, duplicates ---

  @Test
  void createReturns201WithLocationVersionZeroAndTheAuditFields() {
    Reply reply = send(USER, HttpMethod.POST, BASE, body("name", "Example", "description", "x"));

    assertThat(reply.status()).isEqualTo(201);
    JsonNode data = reply.data();
    String id = data.get("id").asString();
    assertThat(UUID.fromString(id).version()).isEqualTo(7);
    assertThat(reply.headers().getLocation()).hasToString(BASE + "/" + id);
    assertThat(reply.body().get("success").asBoolean()).isTrue();
    assertThat(reply.body().get("message").asString()).isEqualTo("Sample record created");
    assertThat(data.get("name").asString()).isEqualTo("Example");
    assertThat(data.get("description").asString()).isEqualTo("x");
    assertThat(data.get("version").asLong()).isZero();
    assertThat(data.get("createdBy").asString()).isEqualTo(USER_ACTOR);
    assertThat(data.get("updatedBy").asString()).isEqualTo(USER_ACTOR);
    assertThat(Instant.parse(data.get("createdAt").asString())).isEqualTo(START);
    assertThat(Instant.parse(data.get("updatedAt").asString())).isEqualTo(START);
    assertThat(reply.body().has("pagination")).isFalse();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource({
    "blank name, POST, '', 0, 0, name, NotBlank",
    "101-char name, POST, LONG, 0, 0, name, Size",
    "1001-char description, POST, ok, 1001, 0, description, Size",
    "missing version, PUT, ok, 0, -1, version, NotNull",
    "negative version, PUT, ok, 0, -2, version, PositiveOrZero"
  })
  void invalidBodiesAre400NamingTheFieldAndConstraint(
      String label,
      String method,
      String name,
      int descriptionLength,
      int version,
      String field,
      String code) {
    Map<String, Object> request = new HashMap<>();
    request.put("name", name.equals("LONG") ? "n".repeat(101) : name);
    request.put("description", "d".repeat(descriptionLength));
    if (version == -2) {
      request.put("version", -1);
    }
    String uri = BASE;
    if (method.equals("PUT")) {
      uri = BASE + "/" + create("validated").get("id").asString();
    }

    Reply reply = send(USER, HttpMethod.valueOf(method), uri, request);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.errorCode()).isEqualTo("VALIDATION_FAILED");
    assertThat(reply.body().get("errors").get(0).get("field").asString()).isEqualTo(field);
    assertThat(reply.body().get("errors").get(0).get("code").asString()).isEqualTo(code);
    assertThat(reply.body().get("errors")).hasSize(1);
  }

  @Test
  void anActiveNameIsTakenButADeletedRecordsNameMayBeReused() {
    JsonNode first = create("Taken");

    Reply duplicate = send(USER, HttpMethod.POST, BASE, body("name", "Taken"));
    assertThat(duplicate.status()).isEqualTo(409);
    assertThat(duplicate.errorCode()).isEqualTo("SAMPLE_RECORD_ALREADY_EXISTS");

    assertThat(send(ADMIN, HttpMethod.DELETE, item(first), null).status()).isEqualTo(200);
    assertThat(send(USER, HttpMethod.POST, BASE, body("name", "Taken")).status()).isEqualTo(201);
  }

  // --- Read ---

  @Test
  void readReturnsAnActiveRecordAnd404ForUnknownOrDeletedIds() {
    JsonNode created = create("Readable");

    Reply found = send(USER, HttpMethod.GET, item(created), null);
    assertThat(found.status()).isEqualTo(200);
    assertThat(found.body().get("message").asString()).isEqualTo("Sample record retrieved");
    assertThat(found.data()).isEqualTo(created);

    Reply unknown = send(USER, HttpMethod.GET, BASE + "/" + UUID.randomUUID(), null);
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.errorCode()).isEqualTo("SAMPLE_RECORD_NOT_FOUND");

    send(ADMIN, HttpMethod.DELETE, item(created), null);
    Reply deleted = send(USER, HttpMethod.GET, item(created), null);
    assertThat(deleted.status()).isEqualTo(404);
    assertThat(deleted.errorCode()).isEqualTo("SAMPLE_RECORD_NOT_FOUND");
  }

  // --- Update ---

  @Test
  void updateWithTheCurrentVersionReplacesNameAndClearsAnOmittedDescription() {
    JsonNode created =
        send(USER, HttpMethod.POST, BASE, body("name", "Before", "description", "x")).data();
    clock.advance(Duration.ofMinutes(5));

    Reply reply = send(ADMIN, HttpMethod.PUT, item(created), body("name", "After", "version", 0));

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.body().get("message").asString()).isEqualTo("Sample record updated");
    JsonNode data = reply.data();
    assertThat(data.get("name").asString()).isEqualTo("After");
    assertThat(data.get("description").isNull()).isTrue();
    assertThat(data.get("version").asLong()).isEqualTo(1);
    assertThat(Instant.parse(data.get("updatedAt").asString()))
        .isEqualTo(START.plus(Duration.ofMinutes(5)));
    assertThat(data.get("updatedBy").asString()).isEqualTo(ADMIN_ACTOR);
    assertThat(data.get("createdBy").asString()).isEqualTo(USER_ACTOR);
    assertThat(data.get("createdAt").asString()).isEqualTo(created.get("createdAt").asString());
  }

  @Test
  void updateWithAStaleVersionIs409() {
    JsonNode created = create("Stale");
    send(USER, HttpMethod.PUT, item(created), body("name", "Stale-1", "version", 0));

    Reply reply = send(USER, HttpMethod.PUT, item(created), body("name", "Stale-2", "version", 0));

    assertThat(reply.status()).isEqualTo(409);
    assertThat(reply.errorCode()).isEqualTo("CONCURRENT_MODIFICATION");
    assertThat(send(USER, HttpMethod.GET, item(created), null).data().get("name").asString())
        .isEqualTo("Stale-1");
  }

  @Test
  void twoConcurrentUpdatesWithTheSameVersionGiveOne200AndOne409() throws Exception {
    JsonNode created = create("Raced");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<CompletableFuture<Reply>> replies = new ArrayList<>();
      for (String name : List.of("Raced-a", "Raced-b")) {
        replies.add(
            CompletableFuture.supplyAsync(
                () -> {
                  await(start);
                  return send(
                      USER, HttpMethod.PUT, item(created), body("name", name, "version", 0));
                },
                pool));
      }
      start.countDown();

      List<Reply> done = replies.stream().map(CompletableFuture::join).toList();

      assertThat(done).extracting(Reply::status).containsExactlyInAnyOrder(200, 409);
      Reply loser = done.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow();
      assertThat(loser.errorCode()).isEqualTo("CONCURRENT_MODIFICATION");
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void renamingToAnotherActiveRecordsNameIs409() {
    create("Occupied");
    JsonNode other = create("Other");

    Reply reply = send(USER, HttpMethod.PUT, item(other), body("name", "Occupied", "version", 0));

    assertThat(reply.status()).isEqualTo(409);
    assertThat(reply.errorCode()).isEqualTo("SAMPLE_RECORD_ALREADY_EXISTS");
  }

  @Test
  void updatingAnUnknownRecordIs404() {
    Reply reply =
        send(
            USER,
            HttpMethod.PUT,
            BASE + "/" + UUID.randomUUID(),
            body("name", "Nobody", "version", 0));

    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.errorCode()).isEqualTo("SAMPLE_RECORD_NOT_FOUND");
  }

  // --- List ---

  @Test
  void listPagesAndSortsByTheRequestedField() {
    createNumbered(25);

    Reply reply = send(USER, HttpMethod.GET, BASE + "?page=1&size=10&sort=name,asc", null);

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.body().get("message").asString()).isEqualTo("Records retrieved");
    assertThat(names(reply))
        .containsExactlyElementsOf(
            IntStream.rangeClosed(11, 20).mapToObj(SampleRecordApiIT::numbered).toList());
    assertThat(pagination(reply))
        .isEqualTo(
            Map.of("page", 1, "size", 10, "totalElements", 25, "totalPages", 3, "hasNext", true));
  }

  @Test
  void aSortWithoutDirectionIsAscendingAndTheDefaultIsNewestFirst() {
    createNumbered(3);

    assertThat(names(send(USER, HttpMethod.GET, BASE + "?sort=name", null)))
        .containsExactly(numbered(1), numbered(2), numbered(3));
    assertThat(names(send(USER, HttpMethod.GET, BASE + "?sort=name,DESC", null)))
        .containsExactly(numbered(3), numbered(2), numbered(1));
    // createNumbered moves the clock a second per record.
    assertThat(names(send(USER, HttpMethod.GET, BASE, null)))
        .containsExactly(numbered(3), numbered(2), numbered(1));
    Reply defaults = send(USER, HttpMethod.GET, BASE, null);
    assertThat(defaults.body().get("pagination").get("size").asInt()).isEqualTo(20);
    assertThat(defaults.body().get("pagination").get("page").asInt()).isZero();
  }

  @Test
  void aPagePastTheLastIsEmptyWithCorrectPagination() {
    createNumbered(25);

    Reply reply = send(USER, HttpMethod.GET, BASE + "?page=5&size=10", null);

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.data().isArray()).isTrue();
    assertThat(reply.data()).isEmpty();
    assertThat(pagination(reply))
        .isEqualTo(
            Map.of("page", 5, "size", 10, "totalElements", 25, "totalPages", 3, "hasNext", false));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource({
    "'sort=description', sort, SortField",
    "'sort=name,up', sort, SortDirection",
    "'size=101', size, Max",
    "'size=0', size, Min",
    "'page=-1', page, Min",
    "'size=abc', size, typeMismatch",
    "'page=abc', page, typeMismatch"
  })
  void badPagingParametersAre400NamingTheParameter(String query, String field, String code) {
    Reply reply = send(USER, HttpMethod.GET, BASE + "?" + query, null);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.errorCode()).isEqualTo("VALIDATION_FAILED");
    assertThat(reply.body().get("errors")).hasSize(1);
    assertThat(reply.body().get("errors").get(0).get("field").asString()).isEqualTo(field);
    assertThat(reply.body().get("errors").get(0).get("code").asString()).isEqualTo(code);
  }

  // --- Delete and restore ---

  @Test
  void adminDeleteHidesTheRecordAndRestoreBringsItBack() {
    JsonNode kept = create("Kept");
    JsonNode removed = create("Removed");
    assertThat(total()).isEqualTo(2);

    clock.advance(Duration.ofMinutes(1));
    Reply deleted = send(ADMIN, HttpMethod.DELETE, item(removed), null);
    assertThat(deleted.status()).isEqualTo(200);
    assertThat(deleted.body().get("message").asString()).isEqualTo("Sample record deleted");
    assertThat(deleted.body().has("data")).isTrue();
    assertThat(deleted.data().isNull()).isTrue();
    assertThat(send(USER, HttpMethod.GET, item(removed), null).status()).isEqualTo(404);
    assertThat(total()).isEqualTo(1);
    assertThat(names(send(USER, HttpMethod.GET, BASE, null))).containsExactly("Kept");
    long versionWhenDeleted =
        jdbc.queryForObject(
            "SELECT version FROM sample.sample_record WHERE id = ?::uuid",
            Long.class,
            removed.get("id").asString());

    clock.advance(Duration.ofMinutes(1));
    Reply restored = send(ADMIN, HttpMethod.POST, item(removed) + "/restore", null);
    assertThat(restored.status()).isEqualTo(200);
    assertThat(restored.body().get("message").asString()).isEqualTo("Sample record restored");
    assertThat(restored.data().get("id").asString()).isEqualTo(removed.get("id").asString());
    assertThat(restored.data().get("version").asLong()).isEqualTo(versionWhenDeleted + 1);
    assertThat(Instant.parse(restored.data().get("updatedAt").asString()))
        .isEqualTo(START.plus(Duration.ofMinutes(2)));
    assertThat(restored.data().get("updatedBy").asString()).isEqualTo(ADMIN_ACTOR);

    assertThat(send(USER, HttpMethod.GET, item(removed), null).status()).isEqualTo(200);
    assertThat(total()).isEqualTo(2);
    assertThat(names(send(USER, HttpMethod.GET, BASE + "?sort=name", null)))
        .containsExactly(kept.get("name").asString(), "Removed");
  }

  @Test
  void aUserMayNeitherDeleteNorRestore() {
    JsonNode created = create("Guarded");

    Reply delete = send(USER, HttpMethod.DELETE, item(created), null);
    assertThat(delete.status()).isEqualTo(403);
    assertThat(delete.errorCode()).isEqualTo("FORBIDDEN");

    send(ADMIN, HttpMethod.DELETE, item(created), null);
    Reply restore = send(USER, HttpMethod.POST, item(created) + "/restore", null);
    assertThat(restore.status()).isEqualTo(403);
    assertThat(restore.errorCode()).isEqualTo("FORBIDDEN");
  }

  @Test
  void restoreIs409ForAnActiveRecord404ForAnUnknownOneAnd409WhenTheNameIsTaken() {
    JsonNode active = create("Active");
    Reply activeReply = send(ADMIN, HttpMethod.POST, item(active) + "/restore", null);
    assertThat(activeReply.status()).isEqualTo(409);
    assertThat(activeReply.errorCode()).isEqualTo("INVALID_STATE_TRANSITION");

    Reply unknown = send(ADMIN, HttpMethod.POST, BASE + "/" + UUID.randomUUID() + "/restore", null);
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.errorCode()).isEqualTo("SAMPLE_RECORD_NOT_FOUND");

    send(ADMIN, HttpMethod.DELETE, item(active), null);
    create("Active");
    Reply taken = send(ADMIN, HttpMethod.POST, item(active) + "/restore", null);
    assertThat(taken.status()).isEqualTo(409);
    assertThat(taken.errorCode()).isEqualTo("SAMPLE_RECORD_ALREADY_EXISTS");
    assertThat(send(USER, HttpMethod.GET, item(active), null).status()).isEqualTo(404);
  }

  // --- No token ---

  @ParameterizedTest(name = "[{index}] {0} {1}")
  @CsvSource({"GET, ''", "POST, ''", "GET, /ID", "PUT, /ID", "DELETE, /ID", "POST, /ID/restore"})
  void everyEndpointNeedsAToken(String method, String suffix) {
    String uri = BASE + suffix.replace("ID", UUID.randomUUID().toString());
    Object request = method.equals("GET") || method.equals("DELETE") ? null : body("name", "x");

    Reply reply = send(null, HttpMethod.valueOf(method), uri, request);

    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.errorCode()).isEqualTo("UNAUTHENTICATED");
  }

  // --- Role checks run before body binding ---

  @ParameterizedTest(name = "[{index}] {0} with a {1} body")
  @CsvSource({"POST, valid", "POST, invalid", "PUT, valid", "PUT, invalid"})
  void aRoleLessCallerIs403WhateverTheBody(String method, String body) {
    String uri = method.equals("PUT") ? item(create("Role-checked")) : BASE;
    Map<String, Object> request =
        body.equals("valid") ? body("name", "Roleless", "version", 0) : body("name", "");

    Reply reply = send(TestJwt.builder().sign(), HttpMethod.valueOf(method), uri, request);

    assertThat(reply.status()).isEqualTo(403);
    assertThat(reply.errorCode()).isEqualTo("FORBIDDEN");
    assertThat(reply.body().get("errors")).isEmpty();
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void aForbiddenCallIsLoggedAtWarn(CapturedOutput output) {
    JsonNode created = create("Warned");
    String correlationId = "forbidden-" + UUID.randomUUID();

    Reply reply =
        send(
            USER,
            HttpMethod.DELETE,
            item(created),
            null,
            Map.of("X-Correlation-Id", correlationId));

    assertThat(reply.status()).isEqualTo(403);
    assertThat(EcsLogLines.of(output.getOut(), GlobalExceptionHandler.class.getName()))
        .anySatisfy(
            line -> {
              assertThat(line.field("correlationId")).isEqualTo(correlationId);
              assertThat(line.level()).isEqualTo("WARN");
              assertThat(line.message()).contains("403");
            });
  }

  // --- More errors ---

  @Test
  void deletingAnUnknownOrAnAlreadyDeletedRecordIs404() {
    JsonNode created = create("Deleted twice");
    assertThat(send(ADMIN, HttpMethod.DELETE, item(created), null).status()).isEqualTo(200);

    for (String uri : List.of(item(created), BASE + "/" + UUID.randomUUID())) {
      Reply reply = send(ADMIN, HttpMethod.DELETE, uri, null);
      assertThat(reply.status()).as(uri).isEqualTo(404);
      assertThat(reply.errorCode()).isEqualTo("SAMPLE_RECORD_NOT_FOUND");
    }
  }

  @Test
  void aRestoredRecordCanBeDeletedAgain() {
    JsonNode created = create("Round trip");
    send(ADMIN, HttpMethod.DELETE, item(created), null);
    send(ADMIN, HttpMethod.POST, item(created) + "/restore", null);

    assertThat(send(ADMIN, HttpMethod.DELETE, item(created), null).status()).isEqualTo(200);
    assertThat(send(USER, HttpMethod.GET, item(created), null).status()).isEqualTo(404);
  }

  @Test
  void aNonUuidIdAndMalformedJsonAre400MalformedRequest() {
    Reply badId = send(USER, HttpMethod.GET, BASE + "/not-a-uuid", null);
    assertThat(badId.status()).isEqualTo(400);
    assertThat(badId.errorCode()).isEqualTo("MALFORMED_REQUEST");

    Reply badJson = send(USER, HttpMethod.POST, BASE, "{\"name\": ");
    assertThat(badJson.status()).isEqualTo(400);
    assertThat(badJson.errorCode()).isEqualTo("MALFORMED_REQUEST");
  }

  // --- The samples of docs/validation-rules.md ---

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource({
    "whitespace-only name, '   ', false, name, NotBlank",
    "missing name, MISSING, false, name, NotBlank",
    "NUL in name, NUL, false, name, Pattern",
    "NUL in description, ok, true, description, Pattern"
  })
  void rejectedSamples(
      String label, String name, boolean nulDescription, String field, String code) {
    Map<String, Object> request = new HashMap<>();
    if (!name.equals("MISSING")) {
      request.put("name", name.equals("NUL") ? "a\u0000b" : name);
    }
    if (nulDescription) {
      request.put("description", "a\u0000b");
    }

    Reply reply = send(USER, HttpMethod.POST, BASE, request);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body().get("errors").get(0).get("field").asString()).isEqualTo(field);
    assertThat(reply.body().get("errors").get(0).get("code").asString()).isEqualTo(code);
  }

  @Test
  void acceptedSamples() {
    assertThat(
            send(
                    USER,
                    HttpMethod.POST,
                    BASE,
                    body("name", "n".repeat(100), "description", "d".repeat(1000)))
                .status())
        .isEqualTo(201);
    Reply empty = send(USER, HttpMethod.POST, BASE, body("name", "Empty", "description", ""));
    assertThat(empty.status()).isEqualTo(201);
    assertThat(empty.data().get("description").asString()).isEmpty();
  }

  @Test
  void namesAreStoredWithoutSurroundingWhitespaceButKeepTheirCase() {
    Reply padded = send(USER, HttpMethod.POST, BASE, body("name", "  Example "));
    assertThat(padded.status()).isEqualTo(201);
    assertThat(padded.data().get("name").asString()).isEqualTo("Example");

    Reply sameName = send(USER, HttpMethod.POST, BASE, body("name", "Example "));
    assertThat(sameName.status()).isEqualTo(409);
    assertThat(sameName.errorCode()).isEqualTo("SAMPLE_RECORD_ALREADY_EXISTS");
    assertThat(send(USER, HttpMethod.POST, BASE, body("name", "example")).status()).isEqualTo(201);
  }

  // --- Helpers ---

  private record Reply(int status, HttpHeaders headers, JsonNode body) {

    JsonNode data() {
      return body.get("data");
    }

    String errorCode() {
      return body.get("errorCode").asString();
    }
  }

  private Reply send(String token, HttpMethod method, String uri, Object body) {
    return send(token, method, uri, body, Map.of());
  }

  private Reply send(
      String token, HttpMethod method, String uri, Object body, Map<String, String> headers) {
    RestClient client =
        token == null ? LenientRestClient.anonymous(port) : LenientRestClient.forPort(port, token);
    RestClient.RequestBodySpec request = client.method(method).uri(uri);
    headers.forEach(request::header);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    ResponseEntity<String> response = request.retrieve().toEntity(String.class);
    JsonNode json = response.getBody() == null ? null : JSON.readTree(response.getBody());
    return new Reply(response.getStatusCode().value(), response.getHeaders(), json);
  }

  private JsonNode create(String name) {
    Reply reply = send(USER, HttpMethod.POST, BASE, body("name", name));
    assertThat(reply.status()).as("create %s", name).isEqualTo(201);
    return reply.data();
  }

  /** {@code count} records named {@code rec-01}…, one second apart. */
  private void createNumbered(int count) {
    for (int number = 1; number <= count; number++) {
      create(numbered(number));
      clock.advance(Duration.ofSeconds(1));
    }
  }

  private static String numbered(int number) {
    return "rec-%02d".formatted(number);
  }

  private long total() {
    return send(USER, HttpMethod.GET, BASE, null)
        .body()
        .get("pagination")
        .get("totalElements")
        .asLong();
  }

  private static List<String> names(Reply reply) {
    List<String> names = new ArrayList<>();
    reply.data().forEach(record -> names.add(record.get("name").asString()));
    return names;
  }

  private static Map<String, Object> pagination(Reply reply) {
    JsonNode pagination = reply.body().get("pagination");
    return Map.of(
        "page", pagination.get("page").asInt(),
        "size", pagination.get("size").asInt(),
        "totalElements", pagination.get("totalElements").asInt(),
        "totalPages", pagination.get("totalPages").asInt(),
        "hasNext", pagination.get("hasNext").asBoolean());
  }

  private static String item(JsonNode sampleRecord) {
    return BASE + "/" + sampleRecord.get("id").asString();
  }

  private static Map<String, Object> body(Object... keysAndValues) {
    Map<String, Object> body = new HashMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      body.put((String) keysAndValues[index], keysAndValues[index + 1]);
    }
    return body;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }
}
