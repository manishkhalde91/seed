package com.company.fixtures.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only: lets a web {@code @SpringBootTest} accept {@link TestJwt} tokens. The decoder verifies
 * signatures with the test public key instead of the issuer's JWKS, and then applies the production
 * {@code jwtValidator} bean, so the timestamp, issuer and audience rules under test are the real
 * ones. Not for {@code webEnvironment = NONE} contexts, which have no security chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurity {

  @Bean
  @Primary
  JwtDecoder testJwtDecoder(OAuth2TokenValidator<Jwt> jwtValidator) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
    decoder.setJwtValidator(jwtValidator);
    return decoder;
  }
}
