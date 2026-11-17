package com.company.fixtures.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Test-only OIDC issuer on a local port: serves the discovery document and a JWKS holding {@link
 * TestJwt#publicKey()}, so the production {@code JwtDecoder} can be exercised end to end. Tokens
 * for it are minted with {@code TestJwt.builder().issuer(issuer.issuer())}.
 */
public final class StubIssuer implements AutoCloseable {

  private final String issuer;
  private final HttpServer server;

  private StubIssuer(String issuer, HttpServer server) {
    this.issuer = issuer;
    this.server = server;
  }

  /**
   * Starts the issuer; stop it with {@link #close()} once its test class is done. Left running, its
   * loopback port can be handed to a later test's Tomcat, which binds the wildcard address with
   * address reuse on: requests to {@code localhost} would then reach this server instead.
   */
  public static StubIssuer start() {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/seed";
      String discovery =
          """
          {"issuer":"%s","jwks_uri":"%s/jwks","subject_types_supported":["public"],\
          "id_token_signing_alg_values_supported":["RS256"],"response_types_supported":["code"]}
          """
              .formatted(issuer, issuer);
      String jwks =
          new JWKSet(new RSAKey.Builder(TestJwt.publicKey()).algorithm(JWSAlgorithm.RS256).build())
              .toString();
      server.createContext(
          "/seed/.well-known/openid-configuration", exchange -> json(exchange, discovery));
      server.createContext("/seed/jwks", exchange -> json(exchange, jwks));
      server.start();
      return new StubIssuer(issuer, server);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** The issuer URI ({@code OIDC_ISSUER_URI}). */
  public String issuer() {
    return issuer;
  }

  private static void json(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
