package com.company.seed.common.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.limits.LimitsProbeController;
import com.company.fixtures.limits.LimitsTestSupport;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The request body limits with their defaults (security.md §6): 1 MB (1,048,576 bytes) for every
 * body that is not {@code multipart/form-data}, 10 MB per upload. Story 7b matrix rows "Exactly at
 * limit", "Over limit, length known", "Over limit, chunked" and "Multipart".
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.com.company.seed.common.limits=DEBUG")
class RequestSizeIT extends LimitsTestSupport {

  private static final int ONE_MB = 1_048_576;
  private static final int TEN_MB = 10 * ONE_MB;
  private static final String MESSAGE = "Request body exceeds 1MB";
  private static final String FORM_PREFIX = "text=";
  private static final String ALLOWED_ORIGIN = "http://localhost:4200";

  @Test
  void aBodyOfExactlyTheLimitIsHandled() {
    ResponseEntity<byte[]> response = postJson(TestJwt.user(), jsonOfSize(ONE_MB), null);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @Test
  void aChunkedBodyOfExactlyTheLimitIsHandled() {
    Answer answer = postChunked(TestJwt.user(), jsonOfSize(ONE_MB), null);

    assertThat(answer.status()).isEqualTo(201);
    assertThat(probe.invocations()).isEqualTo(1);
  }

  @ParameterizedTest(name = "with token: {0}")
  @ValueSource(booleans = {true, false})
  void aContentLengthOverTheLimitIsRefusedBeforeTheHandler(
      boolean withToken, CapturedOutput output) {
    String correlationId = unique("corr-413");

    ResponseEntity<byte[]> response =
        postJson(withToken ? TestJwt.user() : null, jsonOfSize(ONE_MB + 1), correlationId);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    Map<String, Object> body = parse(response.getBody());
    assertThat(body)
        .containsEntry("success", false)
        .containsEntry("status", 413)
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", MESSAGE)
        .containsEntry("correlationId", correlationId);
    assertThat(probe.invocations()).isZero();
    assertThat(logLines(output, RequestSizeFilter.class, "INFO"))
        .anyMatch(line -> line.message().contains("Content-Length " + (ONE_MB + 1)));
  }

  @Test
  void aChunkedBodyOverTheLimitIsRefusedWhileItIsRead(CapturedOutput output) {
    Answer answer = postChunked(TestJwt.user(), jsonOfSize(ONE_MB + 1), null);

    assertThat(answer.status()).isEqualTo(413);
    assertThat(parse(answer.body()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", MESSAGE);
    assertThat(answer.header("X-Correlation-Id")).isNotBlank();
    assertThat(probe.invocations()).isZero();
    assertThat(logLines(output, GlobalExceptionHandler.class, "INFO"))
        .anyMatch(line -> line.message().contains("413 PAYLOAD_TOO_LARGE"));
    assertNoErrorLogged(output);
  }

  @Test
  void aKeyedChunkedBodyOverTheLimitIsRefusedWithoutAnIdempotencyRow(CapturedOutput output) {
    String key = unique("chunked-too-large");

    Answer answer = postChunked(TestJwt.user(), jsonOfSize(ONE_MB + 1), key);

    assertThat(answer.status()).isEqualTo(413);
    assertThat(parse(answer.body()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", MESSAGE);
    assertThat(probe.invocations()).isZero();
    assertThat(idempotencyRows(key)).isZero();
    assertThat(logLines(output, RequestSizeFilter.class, "INFO"))
        .anyMatch(line -> line.message().contains("read past the 1MB body limit"));
    assertNoErrorLogged(output);
  }

  /** A client's oversized body is not a server error: nothing (the container included) logs one. */
  private static void assertNoErrorLogged(CapturedOutput output) {
    assertThat(EcsLogLines.all(output.getOut())).noneMatch(line -> line.level().equals("ERROR"));
  }

  @Test
  void anUploadOverTheUploadLimitIsRefused(CapturedOutput output) {
    ResponseEntity<byte[]> response = upload(TestJwt.user(), TEN_MB + 1);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    assertThat(parse(response.getBody()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", "Upload exceeds 10MB");
    assertThat(probe.invocations()).isZero();
    assertThat(logLines(output, GlobalExceptionHandler.class, "INFO"))
        .anyMatch(line -> line.message().contains("413 PAYLOAD_TOO_LARGE"));
  }

  /** Only {@code multipart/form-data} has the upload limits; other multipart types have 1 MB. */
  @ParameterizedTest(name = "chunked: {0}")
  @ValueSource(booleans = {true, false})
  void aKeyedMultipartMixedBodyOverTheLimitIsRefusedWithoutAnIdempotencyRow(boolean chunked) {
    String key = unique("mixed-too-large");

    Answer answer =
        post(
            TestJwt.user(),
            LimitsProbeController.WRITES,
            "multipart/mixed; boundary=seed",
            new byte[ONE_MB + 1],
            key,
            chunked);

    assertThat(answer.status()).isEqualTo(413);
    assertThat(parse(answer.body()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", MESSAGE);
    assertThat(probe.invocations()).isZero();
    assertThat(idempotencyRows(key)).isZero();
  }

  /** The container parses form posts; a body over the limit is 413, not dropped parameters. */
  @ParameterizedTest(name = "chunked: {0}")
  @ValueSource(booleans = {true, false})
  void aFormPostOverTheLimitIsRefused(boolean chunked, CapturedOutput output) {
    byte[] form = formOfSize(ONE_MB + 1);

    Answer answer =
        post(
            TestJwt.user(),
            LimitsProbeController.FORM,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            form,
            null,
            chunked);

    assertThat(answer.status()).isEqualTo(413);
    assertThat(parse(answer.body()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", MESSAGE);
    assertThat(probe.invocations()).isZero();
    assertNoErrorLogged(output);
  }

  @ParameterizedTest(name = "chunked: {0}")
  @ValueSource(booleans = {true, false})
  void aFormPostOfExactlyTheLimitIsHandled(boolean chunked) {
    Answer answer =
        post(
            TestJwt.user(),
            LimitsProbeController.FORM,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            formOfSize(ONE_MB),
            null,
            chunked);

    assertThat(answer.status()).isEqualTo(200);
    assertThat(parse(answer.body())).containsEntry("data", ONE_MB - FORM_PREFIX.length());
  }

  /** The early 413 is answered after the browser rules: CORS and the security headers. */
  @Test
  void anEarly413CarriesTheCorsAndSecurityHeaders() {
    ResponseEntity<byte[]> response =
        client(TestJwt.user())
            .post()
            .uri(LimitsProbeController.BODY)
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(ONE_MB + 1)
            .body(jsonOfSize(ONE_MB + 1))
            .retrieve()
            .toEntity(byte[].class);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
  }

  /** {@code text=xxx…} of exactly {@code bytes} bytes. */
  private static byte[] formOfSize(int bytes) {
    return (FORM_PREFIX + "x".repeat(bytes - FORM_PREFIX.length()))
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** The 1 MB JSON limit does not apply to uploads. */
  @ParameterizedTest(name = "{0} bytes")
  @ValueSource(ints = {1024, 2 * ONE_MB})
  void anUploadWithinTheUploadLimitIsHandled(int bytes) {
    ResponseEntity<byte[]> response = upload(TestJwt.user(), bytes);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(parse(response.getBody())).containsEntry("data", bytes);
    assertThat(probe.invocations()).isEqualTo(1);
  }
}
