package com.company.seed.common.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.limits.LimitsTestSupport;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The limits are configuration (story 7b matrix rows "Configurable" and the 5/min part of "Rate
 * over budget"): 5 writes per minute, a 2 KB body limit and, through its environment variable, a 1
 * KB upload limit.
 */
@TestPropertySource(
    properties = {
      "seed.limits.writes-per-minute=5",
      "seed.limits.max-body-size=2KB",
      "SEED_MAX_UPLOAD_SIZE=1KB"
    })
class ConfiguredLimitsIT extends LimitsTestSupport {

  private static final int BUDGET = 5;

  private void write(String token, int count) {
    for (int i = 0; i < count; i++) {
      assertThat(send(HttpMethod.POST, token).getStatusCode().value())
          .as("write %d", i + 1)
          .isEqualTo(201);
    }
  }

  @Test
  void theSixthWriteIsRefused() {
    String caller = newCaller();
    write(caller, BUDGET);

    ResponseEntity<byte[]> sixth = send(HttpMethod.PUT, caller);

    assertThat(sixth.getStatusCode().value()).isEqualTo(429);
    assertThat(parse(sixth.getBody())).containsEntry("errorCode", ErrorCode.RATE_LIMITED);
    assertThat(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
  }

  /**
   * At 5/min a token takes 12 s. {@code Retry-After} is the wait for one token rounded up to a
   * whole second: 1 s after the 5th write the wait is exactly 11 s; half a second after, it is 11.5
   * s, rounded up to 12.
   */
  @ParameterizedTest(name = "6th write {0} ms after the 5th -> Retry-After: {1}")
  @CsvSource({"0, 12", "500, 12", "1000, 11"})
  void retryAfterIsTheWaitForOneTokenRoundedUp(long afterMillis, String retryAfter) {
    String caller = newCaller();
    write(caller, BUDGET);

    clock.advance(Duration.ofMillis(afterMillis));
    ResponseEntity<byte[]> sixth = send(HttpMethod.POST, caller);

    assertThat(sixth.getStatusCode().value()).isEqualTo(429);
    assertThat(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
  }

  @Test
  void aBodyOverTheConfiguredLimitIsRefused() {
    ResponseEntity<byte[]> atLimit = postJson(TestJwt.user(), jsonOfSize(2048), null);
    ResponseEntity<byte[]> overLimit = postJson(TestJwt.user(), jsonOfSize(2049), null);
    Answer chunked = postChunked(TestJwt.user(), jsonOfSize(2049), null);

    assertThat(atLimit.getStatusCode().value()).isEqualTo(201);
    assertThat(overLimit.getStatusCode().value()).isEqualTo(413);
    assertThat(parse(overLimit.getBody()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", "Request body exceeds 2KB");
    assertThat(chunked.status()).isEqualTo(413);
    assertThat(parse(chunked.body())).containsEntry("message", "Request body exceeds 2KB");
  }

  @Test
  void anUploadOverTheConfiguredUploadLimitIsRefused() {
    ResponseEntity<byte[]> overLimit = upload(TestJwt.user(), 1025);

    assertThat(overLimit.getStatusCode().value()).isEqualTo(413);
    assertThat(parse(overLimit.getBody()))
        .containsEntry("errorCode", ErrorCode.PAYLOAD_TOO_LARGE)
        .containsEntry("message", "Upload exceeds 1KB");
    assertThat(probe.invocations()).isZero();
  }
}
