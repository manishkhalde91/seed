package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.StatusProbe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The {@code dev} profile turns the API docs on when {@code SEED_API_DOCS_ENABLED} is unset. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({TestDatabase.class, TestSecurity.class})
class DevApiDocsIT {

  @LocalServerPort private int port;

  @Test
  void docsAndSwaggerUiAreServed() {
    assertThat(StatusProbe.status(port, "/v3/api-docs")).isEqualTo(200);
    assertThat(StatusProbe.status(port, "/swagger-ui/index.html")).isEqualTo(200);
  }
}
