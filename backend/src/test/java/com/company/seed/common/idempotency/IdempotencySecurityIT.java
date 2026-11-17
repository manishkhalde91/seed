package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.idempotency.IdempotencyProbeController;
import com.company.fixtures.idempotency.IdempotencyTestSupport;
import com.company.fixtures.security.TestJwt;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.security.Roles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * {@code Idempotency-Key} behind authentication (story 7): records belong to the caller, a replay
 * needs a valid token and carries the security headers, and answers that say nothing about the
 * request (401, 403) are never stored.
 */
class IdempotencySecurityIT extends IdempotencyTestSupport {

  private static final String ITEMS = IdempotencyProbeController.BASE + "/items";
  private static final String ADMIN_ONLY = IdempotencyProbeController.BASE + "/admin";
  private static final String BODY = "{\"name\":\"scoped\"}";

  private final String callerA = TestJwt.builder().subject("a").roles(Roles.USER).sign();
  private final String callerB = TestJwt.builder().subject("b").roles(Roles.USER).sign();

  @Test
  void theSameKeyFromTwoCallersIsTwoIndependentRecords() {
    String key = unique("two-callers");

    ResponseEntity<byte[]> fromA = post(client(callerA), ITEMS, key);
    ResponseEntity<byte[]> fromB = post(client(callerB), ITEMS, key);

    assertThat(fromA.getStatusCode().value()).isEqualTo(201);
    assertThat(fromB.getStatusCode().value()).isEqualTo(201);
    assertThat(fromB.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER)).isFalse();
    assertThat(probe.invocations()).isEqualTo(2);
    assertThat(rows(key))
        .extracting(row -> row.get("caller"))
        .containsExactlyInAnyOrder("user:a", "user:b");
  }

  @Test
  void aReplayNeedsATokenAndThenCarriesTheSecurityHeaders() {
    String key = unique("replay");
    ResponseEntity<byte[]> first = post(client(callerA), ITEMS, key);

    ResponseEntity<byte[]> withoutToken = post(anonymousClient(), ITEMS, key);
    ResponseEntity<byte[]> withToken = post(client(callerA), ITEMS, key);

    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(withoutToken.getStatusCode().value()).isEqualTo(401);
    assertThat(parse(withoutToken.getBody())).containsEntry("errorCode", ErrorCode.UNAUTHENTICATED);
    assertThat(withoutToken.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER))
        .isFalse();
    assertThat(withToken.getStatusCode().value()).isEqualTo(201);
    assertThat(withToken.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER))
        .isEqualTo("true");
    assertThat(withToken.getBody()).isEqualTo(first.getBody());
    HttpHeaders headers = withToken.getHeaders();
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
    assertThat(probe.invocations()).isEqualTo(1);
    assertThat(rows(key)).hasSize(1);
  }

  @Test
  void aKeyedRequestRefusedByMethodSecurityIsNeverStored() {
    String key = unique("forbidden");

    ResponseEntity<byte[]> first = post(client(callerA), ADMIN_ONLY, key);
    var rowsAfterFirst = rows(key);
    ResponseEntity<byte[]> retry = post(client(callerA), ADMIN_ONLY, key);

    assertThat(List.of(first, retry))
        .allSatisfy(
            response -> {
              assertThat(response.getStatusCode().value()).isEqualTo(403);
              assertThat(parse(response.getBody())).containsEntry("errorCode", ErrorCode.FORBIDDEN);
              assertThat(response.getHeaders().containsHeader(IdempotencyFilter.REPLAYED_HEADER))
                  .isFalse();
            });
    assertThat(rowsAfterFirst).isEmpty();
    assertThat(rows(key)).isEmpty();
    assertThat(probe.invocations()).isEqualTo(2);
  }

  private static ResponseEntity<byte[]> post(RestClient client, String path, String key) {
    return client
        .post()
        .uri(path)
        .header(IdempotencyFilter.HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(BODY)
        .retrieve()
        .toEntity(byte[].class);
  }
}
