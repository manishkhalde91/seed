package com.company.fixtures.security;

import com.company.seed.security.Roles;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only: mints RS256 access tokens as the identity provider would, signed with a key pair made
 * once per JVM. {@link TestSecurity} decodes them with that public key and the production validator
 * bean, so issuer, audience and timestamps are checked exactly as in production. The issuer and
 * audience match {@code src/test/resources/config/application.yml}.
 *
 * <pre>{@code
 * String token = TestJwt.builder().subject("u-1").roles(Roles.ADMIN).sign();
 * }</pre>
 */
public final class TestJwt {

  public static final String ISSUER = "https://issuer.seed.test/seed";
  public static final String AUDIENCE = "seed-api";

  /** The subject of {@link #user()}. */
  public static final String USER_SUBJECT = "test-user";

  /** The subject of {@link #admin()}. */
  public static final String ADMIN_SUBJECT = "test-admin";

  private static final KeyPair KEYS = newKeyPair();
  private static final KeyPair FOREIGN_KEYS = newKeyPair();

  private TestJwt() {}

  /** The public key that verifies every token signed here. */
  public static RSAPublicKey publicKey() {
    return (RSAPublicKey) KEYS.getPublic();
  }

  /** A valid token for {@value #USER_SUBJECT} with the {@code USER} role. */
  public static String user() {
    return builder().subject(USER_SUBJECT).roles(Roles.USER).sign();
  }

  /** A valid token for {@value #ADMIN_SUBJECT} with the {@code ADMIN} role. */
  public static String admin() {
    return builder().subject(ADMIN_SUBJECT).roles(Roles.ADMIN).sign();
  }

  /** {@code Bearer <token>}, the {@code Authorization} header value. */
  public static String bearer(String token) {
    return "Bearer " + token;
  }

  /**
   * A builder for a valid token (subject {@value #USER_SUBJECT}, no roles, issued now, valid for an
   * hour); change what the test needs.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds one token; every setter replaces the default. */
  public static final class Builder {

    private final Map<String, Object> claims = new LinkedHashMap<>();
    private boolean foreignKey;

    private Builder() {
      Instant now = Instant.now();
      claims.put("sub", USER_SUBJECT);
      claims.put("iss", ISSUER);
      claims.put("aud", List.of(AUDIENCE));
      claims.put("iat", Date.from(now));
      claims.put("exp", Date.from(now.plus(Duration.ofHours(1))));
    }

    public Builder subject(String subject) {
      return claim("sub", subject);
    }

    public Builder issuer(String issuer) {
      return claim("iss", issuer);
    }

    public Builder audience(String... audience) {
      return claim("aud", List.of(audience));
    }

    /** The roles in the default {@code roles} claim. */
    public Builder roles(String... roles) {
      return claim("roles", List.of(roles));
    }

    /**
     * The expiry. A token always expires after it was issued, so an expiry at or before {@code iat}
     * moves {@code iat} to an hour before it.
     */
    public Builder expiresAt(Instant expiresAt) {
      if (claims.get("iat") instanceof Date issuedAt && !expiresAt.isAfter(issuedAt.toInstant())) {
        claim("iat", Date.from(expiresAt.minus(Duration.ofHours(1))));
      }
      return claim("exp", Date.from(expiresAt));
    }

    public Builder notBefore(Instant notBefore) {
      return claim("nbf", Date.from(notBefore));
    }

    /** Any claim; {@code null} removes it. */
    public Builder claim(String name, Object value) {
      if (value == null) {
        claims.remove(name);
      } else {
        claims.put(name, value);
      }
      return this;
    }

    /** Signs with a key the application does not trust: a bad signature. */
    public Builder signedWithForeignKey() {
      this.foreignKey = true;
      return this;
    }

    /** The RS256-signed, serialized token. */
    public String sign() {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet());
      try {
        jwt.sign(new RSASSASigner((foreignKey ? FOREIGN_KEYS : KEYS).getPrivate()));
      } catch (JOSEException ex) {
        throw new IllegalStateException("Cannot sign the test token", ex);
      }
      return jwt.serialize();
    }

    /** The token unsigned, with {@code alg: none}. */
    public String unsigned() {
      return new PlainJWT(claimsSet()).serialize();
    }

    private JWTClaimsSet claimsSet() {
      JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
      claims.forEach(builder::claim);
      return builder.build();
    }
  }

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("RSA is required by every Java platform", ex);
    }
  }
}
