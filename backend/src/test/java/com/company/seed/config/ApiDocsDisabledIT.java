package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.StatusProbe;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** With {@code SEED_API_DOCS_ENABLED} unset (the code default, as in prod) no docs are served. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestSecurity.class})
class ApiDocsDisabledIT {

  @LocalServerPort private int port;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v3/api-docs",
        "/v3/api-docs.yaml",
        "/v3/api-docs/swagger-config",
        "/swagger-ui/index.html",
        "/swagger-ui.html"
      })
  void docsAndSwaggerUiAreNotFound(String path) {
    assertThat(StatusProbe.status(port, path)).isEqualTo(404);
  }
}
