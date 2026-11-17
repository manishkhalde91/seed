package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The indicator outside an application context: it re-checks while DOWN and, once UP, answers UP
 * without reading the history again.
 */
class SchemaVersionHealthIndicatorIT {

  private static final String COMMON = "classpath:db/migration/common";
  private static final String PENDING = "classpath:db/migration/pending";

  @Test
  void reChecksWhileDownAndKeepsUpOnceReached() throws SQLException {
    String database = "schema_indicator";
    TestDatabase.migrate(database, COMMON);
    SchemaVersionHealthIndicator indicator = indicator(database);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

    TestDatabase.migrate(database, COMMON, PENDING);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

    insertFailedMigration(database);
    assertThat(indicator.health().getStatus()).as("cached").isEqualTo(Status.UP);
    assertThat(indicator(database).health().getStatus()).as("fresh").isEqualTo(Status.DOWN);
  }

  private static SchemaVersionHealthIndicator indicator(String database) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            TestDatabase.jdbcUrl(database), TestDatabase.APP_USER, TestDatabase.APP_PASSWORD);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.flyway.locations", COMMON + "," + PENDING)
            .withProperty("spring.flyway.schemas", "common")
            .withProperty("spring.flyway.default-schema", "common");
    return new SchemaVersionHealthIndicator(dataSource, environment);
  }

  private static void insertFailedMigration(String database) throws SQLException {
    try (Connection owner = TestDatabase.connectAsOwner(database);
        Statement statement = owner.createStatement()) {
      statement.execute(
          """
          INSERT INTO common.flyway_schema_history
            (installed_rank, version, description, type, script, checksum, installed_by,
             execution_time, success)
          VALUES (100, '209901010009', 'fixture broken', 'SQL', 'V209901010009__fixture_broken.sql',
                  0, 'seed_owner', 0, false)
          """);
    }
  }
}
