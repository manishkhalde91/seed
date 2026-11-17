package com.company.fixtures.web;

import com.company.fixtures.security.TestJwt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Test-only: a client for the running application that returns every status instead of throwing. By
 * default every request carries a valid {@code USER} token ({@link TestJwt#user()}), as a signed-in
 * client would; {@link #anonymous(int)} sends none.
 */
public final class LenientRestClient {

  private LenientRestClient() {}

  /** A client sending a {@code USER} token. */
  public static RestClient forPort(int port) {
    return forPort(port, TestJwt.user());
  }

  /** A client sending an {@code ADMIN} token. */
  public static RestClient admin(int port) {
    return forPort(port, TestJwt.admin());
  }

  /** A client sending {@code token} as its bearer token. */
  public static RestClient forPort(int port, String token) {
    return builder(port).defaultHeader(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token)).build();
  }

  /** A client sending no token. */
  public static RestClient anonymous(int port) {
    return builder(port).build();
  }

  private static RestClient.Builder builder(int port) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(status -> true, (request, response) -> {});
  }
}
