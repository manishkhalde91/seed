package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link IdempotencyFilter} paths that need a failing or racing database, against a stub
 * repository.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyFilterTest {

  private static final String KEY = "unit-key";
  private static final String CALLER = CurrentActor.USER_PREFIX + "unit";
  private static final int MAX_STORED_RESPONSE_BYTES = 16;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"), ZoneOffset.UTC);

  private final StubRepository repository = new StubRepository();
  private final IdempotencyFilter filter =
      new IdempotencyFilter(
          repository,
          new IdempotencyProperties(
              Duration.ofHours(24),
              Duration.ofMinutes(5),
              DataSize.ofBytes(MAX_STORED_RESPONSE_BYTES)),
          new ApiErrorResponseWriter(CLOCK, JsonMapper.builder().build()),
          () -> CALLER,
          CLOCK);
  private final AtomicInteger handlerRuns = new AtomicInteger();
  private final FilterChain created =
      (request, response) -> {
        handlerRuns.incrementAndGet();
        ((HttpServletResponse) response).setStatus(201);
        response.getOutputStream().write("created".getBytes(StandardCharsets.UTF_8));
      };

  @Test
  void aFailedCompleteStillSendsTheHandlersResponse(CapturedOutput output) throws Exception {
    repository.claims.add(true);
    repository.completeFailure = new IllegalStateException("database down");

    MockHttpServletResponse response = run(keyedPost("{}"), created);

    assertThat(handlerRuns).hasValue(1);
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo("created");
    assertThat(output.getAll()).contains("Could not record the outcome of Idempotency-Key " + KEY);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {401, 403, 408, 429})
  void aResponseThatSaysNothingAboutTheRequestIsReleasedNotStored(int status) throws Exception {
    MockHttpServletResponse response =
        run(
            keyedPost("{}"),
            (request, servletResponse) -> {
              handlerRuns.incrementAndGet();
              ((HttpServletResponse) servletResponse).setStatus(status);
            });

    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(repository.completeCalls).hasValue(0);
    assertThat(repository.releaseCalls).hasValue(1);
  }

  @Test
  void anOrdinaryClientErrorIsStored() throws Exception {
    run(
        keyedPost("{}"),
        (request, servletResponse) -> ((HttpServletResponse) servletResponse).setStatus(422));

    assertThat(repository.completeCalls).hasValue(1);
    assertThat(repository.releaseCalls).hasValue(0);
  }

  @Test
  void runsAfterTheSecurityFilterChain() {
    assertThat(IdempotencyFilter.ORDER).isEqualTo(-90);
  }

  @Test
  void aRowGoneBetweenClaimAndReadIsClaimedAgain() throws Exception {
    repository.claims.add(false);
    repository.claims.add(true);

    MockHttpServletResponse response = run(keyedPost("{}"), created);

    assertThat(repository.claimCalls).hasValue(2);
    assertThat(handlerRuns).hasValue(1);
    assertThat(response.getStatus()).isEqualTo(201);
  }

  @Test
  void aDatabaseFailureWhileClaimingIsServiceUnavailable(CapturedOutput output) throws Exception {
    repository.claimFailure = new IllegalStateException("database down");

    MockHttpServletResponse response = run(keyedPost("{}"), created);

    assertThat(handlerRuns).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains(ErrorCode.SERVICE_UNAVAILABLE);
    assertThat(output.getAll()).contains("Could not claim Idempotency-Key " + KEY);
  }

  /** The request body has only the global limit (RequestSizeFilter); this filter refuses none. */
  @Test
  void theStoredResponseCapDoesNotLimitTheRequestBody() throws Exception {
    repository.claims.add(true);
    MockHttpServletRequest request = keyedPost("x".repeat(MAX_STORED_RESPONSE_BYTES * 4));
    HttpServletRequestWrapper withoutLength =
        new HttpServletRequestWrapper(request) {
          @Override
          public int getContentLength() {
            return -1;
          }

          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(withoutLength, response, created);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(repository.claimCalls).hasValue(1);
    assertThat(handlerRuns).hasValue(1);
    assertThat(repository.completeCalls).hasValue(1);
  }

  @Test
  void anUnknownRequestCharsetIsAnUnsupportedEncoding() throws Exception {
    repository.claims.add(true);
    MockHttpServletRequest request = keyedPost("{}");
    request.setCharacterEncoding("no-such-charset");
    AtomicReference<Exception> thrown = new AtomicReference<>();

    run(
        request,
        (req, res) -> {
          try {
            req.getReader();
          } catch (UnsupportedEncodingException ex) {
            thrown.set(ex);
          }
        });

    assertThat(thrown.get()).isInstanceOf(UnsupportedEncodingException.class);
  }

  private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static MockHttpServletRequest keyedPost(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/things");
    request.addHeader(IdempotencyFilter.HEADER, KEY);
    request.setContentType("application/json");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  /** Claims follow {@link #claims} (default: claimed); every other write succeeds. */
  private static final class StubRepository implements IdempotencyRecordRepository {

    private final Deque<Boolean> claims = new ArrayDeque<>();
    private final AtomicInteger claimCalls = new AtomicInteger();
    private final AtomicInteger completeCalls = new AtomicInteger();
    private final AtomicInteger releaseCalls = new AtomicInteger();
    private RuntimeException claimFailure;
    private RuntimeException completeFailure;

    @Override
    public boolean claim(
        String caller,
        String key,
        String requestHash,
        UUID claimToken,
        Instant now,
        Instant expiredBefore) {
      assertThat(caller).isEqualTo(CALLER);
      claimCalls.incrementAndGet();
      if (claimFailure != null) {
        throw claimFailure;
      }
      return claims.isEmpty() || claims.poll();
    }

    @Override
    public Optional<IdempotencyRecord> findByKey(String caller, String key, Instant expiredBefore) {
      return Optional.empty();
    }

    @Override
    public boolean takeOver(
        String caller,
        String key,
        String requestHash,
        UUID seenClaimToken,
        UUID newClaimToken,
        Instant claimedAtOrBefore,
        Instant now) {
      return false;
    }

    @Override
    public boolean complete(
        String caller, String key, UUID claimToken, StoredResponse response, Instant now) {
      completeCalls.incrementAndGet();
      if (completeFailure != null) {
        throw completeFailure;
      }
      return true;
    }

    @Override
    public boolean release(String caller, String key, UUID claimToken) {
      releaseCalls.incrementAndGet();
      return true;
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
      return 0;
    }
  }
}
