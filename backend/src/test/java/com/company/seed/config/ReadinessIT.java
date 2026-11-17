package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Readiness follows the database, the schema version (data-and-migrations.md §11) and the operation
 * status listener (realtime-and-storage.md §1.2); liveness never does. Each case is its own
 * application context. The cases behind on migrations run with Flyway's startup run off, as shared
 * environments do, so only the {@code schemaVersion} check stands between them and traffic. Details
 * are shown so the DOWN reason can be checked. Their databases get every main migration (whatever
 * modules exist), so Hibernate's schema validation passes, and their Flyway locations are every
 * main location plus the test's own.
 */
class ReadinessIT {

  private static final String PENDING = "classpath:db/migration/pending";
  private static final String PENDING_VERSION = "209901010000";
  private static final String IGNORED_NEWER = "classpath:db/migration/ignored/newer";
  private static final String IGNORED_OLDER = "classpath:db/migration/ignored/older";
  private static final String IGNORED_VERSION = "202611161000";
  private static final String FAILED_VERSION = "209901010002";
  private static final String COMMON_VERSION = "202611160900";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "management.endpoint.health.show-details=always")
  @Import({TestDatabase.class, TestSecurity.class})
  class SchemaCurrent {

    @LocalManagementPort private int managementPort;

    @Test
    void readinessIsUpWithTheDatabaseSchemaAndListenerChecks() {
      Probe readiness = probe(managementPort, "readiness");

      assertThat(readiness.status()).isEqualTo(200);
      assertThat(readiness.components())
          .containsExactlyInAnyOrder(
              "readinessState", "db", "schemaVersion", "operationStatusListener");
      assertThat(readiness.status("schemaVersion")).isEqualTo("UP");
      assertThat(readiness.status("db")).isEqualTo("UP");
      assertThat(readiness.status("operationStatusListener")).isEqualTo("UP");
      assertLive(managementPort);
    }
  }

  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "management.endpoint.health.show-details=always",
        "spring.flyway.enabled=false"
      })
  @Import({TestDatabase.class, TestSecurity.class})
  class PendingMigration {

    private static final String DATABASE = "readiness_pending";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
      TestDatabase.migrate(DATABASE, TestDatabase.mainLocations());
      registry.add(TestDatabase.DATABASE_PROPERTY, () -> DATABASE);
      registry.add(
          "spring.flyway.locations",
          () -> String.join(",", TestDatabase.mainLocationsAnd(PENDING)));
    }

    @LocalManagementPort private int managementPort;

    @Test
    void readinessIsDownNamingThePendingVersion() {
      Probe readiness = probe(managementPort, "readiness");

      assertThat(readiness.status()).isEqualTo(503);
      assertThat(readiness.status("schemaVersion")).isEqualTo("DOWN");
      assertThat(readiness.details("schemaVersion"))
          .contains("\"pendingCount\":1")
          .contains("\"pending\":[\"" + PENDING_VERSION + "\"]");
      assertThat(readiness.status("db")).isEqualTo("UP");
      assertLive(managementPort);
    }
  }

  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "management.endpoint.health.show-details=always",
        "spring.flyway.enabled=false"
      })
  @Import({TestDatabase.class, TestSecurity.class})
  class FailedMigration {

    private static final String DATABASE = "readiness_failed";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
      TestDatabase.migrate(DATABASE, TestDatabase.mainLocations());
      try (Connection owner = TestDatabase.connectAsOwner(DATABASE);
          Statement statement = owner.createStatement()) {
        statement.execute(
            """
            INSERT INTO common.flyway_schema_history
              (installed_rank, version, description, type, script, checksum, installed_by,
               execution_time, success)
            VALUES (100, '%s', 'fixture broken', 'SQL', 'V%s__fixture_broken.sql', 0,
                    'seed_owner', 0, false)
            """
                .formatted(FAILED_VERSION, FAILED_VERSION));
      }
      registry.add(TestDatabase.DATABASE_PROPERTY, () -> DATABASE);
    }

    @LocalManagementPort private int managementPort;

    @Test
    void readinessIsDownNamingTheFailedVersion() {
      Probe readiness = probe(managementPort, "readiness");

      assertThat(readiness.status()).isEqualTo(503);
      assertThat(readiness.status("schemaVersion")).isEqualTo("DOWN");
      assertThat(readiness.details("schemaVersion")).contains("failed").contains(FAILED_VERSION);
      assertLive(managementPort);
    }
  }

  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "management.endpoint.health.show-details=always",
        "spring.flyway.enabled=false"
      })
  @Import({TestDatabase.class, TestSecurity.class})
  class IgnoredMigration {

    private static final String DATABASE = "readiness_ignored";

    /** A newer migration is applied first, so the older one on the classpath is skipped. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
      TestDatabase.migrate(DATABASE, TestDatabase.mainLocationsAnd(IGNORED_NEWER));
      registry.add(TestDatabase.DATABASE_PROPERTY, () -> DATABASE);
      registry.add(
          "spring.flyway.locations",
          () -> String.join(",", TestDatabase.mainLocationsAnd(IGNORED_NEWER, IGNORED_OLDER)));
    }

    @LocalManagementPort private int managementPort;

    @Test
    void readinessIsDownNamingTheIgnoredVersion() {
      Probe readiness = probe(managementPort, "readiness");

      assertThat(readiness.status()).isEqualTo(503);
      assertThat(readiness.status("schemaVersion")).isEqualTo("DOWN");
      assertThat(readiness.details("schemaVersion"))
          .contains("\"ignored\":[\"" + IGNORED_VERSION + "\"]");
      assertLive(managementPort);
    }
  }

  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "management.endpoint.health.show-details=always",
        "spring.flyway.enabled=false",
        TestDatabase.DATABASE_PROPERTY + "=readiness_untouched",
        // With no tables at all, Hibernate's schema validation and the event registry's
        // resubmission of outstanding publications would stop startup before the readiness check
        // could report anything; this case tests the check alone.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.modulith.events.republish-outstanding-events-on-restart=false"
      })
  @Import({TestDatabase.class, TestSecurity.class})
  class NeverMigrated {

    @LocalManagementPort private int managementPort;

    @Test
    void readinessIsDownNamingTheMissingVersion() {
      Probe readiness = probe(managementPort, "readiness");

      assertThat(readiness.status()).isEqualTo(503);
      assertThat(readiness.status("schemaVersion")).isEqualTo("DOWN");
      assertThat(readiness.details("schemaVersion")).contains(COMMON_VERSION);
      assertLive(managementPort);
    }
  }

  /** The only case with a container of its own: it is stopped while the application runs. */
  @Nested
  @DirtiesContext
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "management.endpoint.health.show-details=always")
  @Import({TestDatabase.class, TestSecurity.class})
  class DatabaseStopped {

    private static final PostgreSQLContainer OWN = TestDatabase.newContainer();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
      OWN.start();
      registry.add(
          "spring.datasource.url", () -> TestDatabase.jdbcUrl(OWN, TestDatabase.SHARED_DATABASE));
    }

    @LocalManagementPort private int managementPort;

    @Test
    void readinessGoesDownWhileLivenessStaysUp() {
      assertThat(probe(managementPort, "readiness").status()).isEqualTo(200);

      OWN.stop();

      Probe readiness = probe(managementPort, "readiness");
      assertThat(readiness.status()).isEqualTo(503);
      assertThat(readiness.status("db")).isEqualTo("DOWN");
      // The operation status listener lost its connection with the database.
      assertThat(readiness.status("operationStatusListener")).isEqualTo("DOWN");
      // Once UP, the schema check does not query again: this image's migrations stay applied.
      assertThat(readiness.status("schemaVersion")).isEqualTo("UP");
      assertLive(managementPort);
    }
  }

  private record Probe(int status, JsonNode body) {

    Collection<String> components() {
      return body.path("components").propertyNames();
    }

    String status(String component) {
      return body.path("components").path(component).path("status").asString();
    }

    String details(String component) {
      return body.path("components").path(component).path("details").toString();
    }
  }

  private static Probe probe(int managementPort, String group) {
    ResponseEntity<String> response =
        RestClient.builder()
            .baseUrl("http://localhost:" + managementPort)
            .defaultStatusHandler(status -> true, (request, ignored) -> {})
            .build()
            .get()
            .uri("/actuator/health/" + group)
            .retrieve()
            .toEntity(String.class);
    return new Probe(response.getStatusCode().value(), JSON.readTree(response.getBody()));
  }

  /** Liveness is UP and never looks at the database. */
  private static void assertLive(int managementPort) {
    Probe liveness = probe(managementPort, "liveness");
    assertThat(liveness.status()).isEqualTo(200);
    assertThat(liveness.components()).containsExactly("livenessState");
  }
}
