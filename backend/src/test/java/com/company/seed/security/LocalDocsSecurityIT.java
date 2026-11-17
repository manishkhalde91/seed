package com.company.seed.security;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link DocsSecurityIT} for the {@code local} profile. Runs in the {@code localProfileTest} task:
 * Spring Boot configures logging for the first context of a JVM only, and {@code local} switches it
 * to readable lines.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import({TestDatabase.class, TestSecurity.class})
class LocalDocsSecurityIT {

  @LocalServerPort private int port;

  @Test
  void docsAreReadableWithoutATokenButApiCallsAreNot() {
    DocsSecurityIT.assertDocsOpenAndApiClosed(port);
  }

  @Test
  void theDocumentOffersBearerTokensAndTheIssuersSignIn() {
    DocsSecurityIT.assertSignInOffered(port);
  }
}
