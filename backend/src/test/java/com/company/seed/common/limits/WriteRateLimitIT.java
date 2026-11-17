package com.company.seed.common.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.limits.LimitsProbeController;
import com.company.fixtures.limits.LimitsTestSupport;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.idempotency.IdempotencyFilter;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The write rate limit with its default budget, 60 writes per minute per caller (security.md §6),
 * on a stopped clock. Each test writes as callers of its own. Story 7b matrix rows "Rate under
 * budget", "Rate over budget" (60/min), "Isolation" and "Replay counts"; the 5/min case is in
 * {@link ConfiguredLimitsIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "seed.limits.writes-per-minute=60")
class WriteRateLimitIT extends LimitsTestSupport {

  private static final int BUDGET = 60;
  private static final List<HttpMethod> WRITES =
      List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

  /** {@code count} writes as {@code token}, cycling through the four write methods. */
  private void write(String token, int count) {
    for (int i = 0; i < count; i++) {
      HttpMethod method = WRITES.get(i % WRITES.size());
      assertThat(send(method, token).getStatusCode().is2xxSuccessful())
          .as("%s write %d", method, i + 1)
          .isTrue();
    }
  }

  private void assertRateLimited(ResponseEntity<byte[]> response, String retryAfter) {
    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
    assertThat(parse(response.getBody()))
        .containsEntry("success", false)
        .containsEntry("status", 429)
        .containsEntry("errorCode", ErrorCode.RATE_LIMITED)
        .containsEntry("message", "Too many requests")
        .containsEntry("path", LimitsProbeController.WRITES);
  }

  @Test
  void sixtyWritesOfEveryMethodWithinAMinuteAllReachTheHandler() {
    String caller = newCaller();

    write(caller, BUDGET);

    assertThat(probe.invocations()).isEqualTo(BUDGET);
  }

  @Test
  void theSixtyFirstWriteIsRefusedUntilATokenRefills(CapturedOutput output) {
    String caller = newCaller();
    write(caller, BUDGET);

    ResponseEntity<byte[]> refused = send(HttpMethod.POST, caller);
    clock.advance(Duration.ofSeconds(1));
    ResponseEntity<byte[]> afterASecond = send(HttpMethod.PATCH, caller);
    ResponseEntity<byte[]> next = send(HttpMethod.DELETE, caller);

    assertRateLimited(refused, "1");
    assertThat(afterASecond.getStatusCode().value()).isEqualTo(200);
    assertRateLimited(next, "1");
    assertThat(probe.invocations()).isEqualTo(BUDGET + 1);
    assertThat(logLines(output, WriteRateLimitFilter.class, "INFO"))
        .anyMatch(
            line ->
                line.message().contains("write rate limit reached")
                    && line.message().contains("user:limits-"));
  }

  @Test
  void anotherCallerAndReadsAreNotLimited() {
    String exhausted = newCaller();
    write(exhausted, BUDGET);
    assertRateLimited(send(HttpMethod.POST, exhausted), "1");

    ResponseEntity<byte[]> other = send(HttpMethod.POST, newCaller());
    List<Integer> reads =
        IntStream.range(0, 5)
            .mapToObj(i -> send(HttpMethod.GET, exhausted).getStatusCode().value())
            .toList();

    assertThat(other.getStatusCode().value()).isEqualTo(201);
    assertThat(reads).containsOnly(200);
  }

  @Test
  void anIdempotentReplayCountsAsAWrite() {
    String caller = newCaller();
    String key = unique("replay-counts");
    write(caller, BUDGET - 1);

    ResponseEntity<byte[]> first = keyedPost(caller, key);
    ResponseEntity<byte[]> replay = keyedPost(caller, key);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertRateLimited(replay, "1");
    assertThat(replay.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(idempotencyRows(key)).isEqualTo(1);
  }

  @Test
  void anOversizedContentLengthCostsNoToken() {
    String caller = newCaller();
    write(caller, BUDGET - 1);

    ResponseEntity<byte[]> tooLarge = postJson(caller, jsonOfSize(1_048_577), null);
    ResponseEntity<byte[]> last = send(HttpMethod.POST, caller);

    assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
    assertThat(last.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void anOversizedChunkedBodyCostsOneToken() {
    String caller = newCaller();
    write(caller, BUDGET - 1);

    Answer tooLarge = postChunked(caller, jsonOfSize(1_048_577), null);
    ResponseEntity<byte[]> next = send(HttpMethod.POST, caller);

    assertThat(tooLarge.status()).isEqualTo(413);
    assertRateLimited(next, "1");
  }
}
