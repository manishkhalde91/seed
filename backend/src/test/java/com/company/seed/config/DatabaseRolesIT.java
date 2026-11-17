package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.db.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The role separation of data-and-migrations.md §10, as the main migration and {@code 01-roles.sh}
 * set it up: {@code seed_app} reads and writes what {@code seed_owner} creates, in {@code common}
 * and in any other owner-created schema it may use, but creates, alters and drops nothing. Runs on
 * its own throwaway database, migrated by the application on startup.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = TestDatabase.DATABASE_PROPERTY + "=" + DatabaseRolesIT.DATABASE)
@Import(TestDatabase.class)
class DatabaseRolesIT {

  static final String DATABASE = "roles_check";
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Autowired private DataSource dataSource;

  /** What a module migration does: a table in common, a schema of its own granted to the app. */
  @BeforeAll
  static void ownerCreatesTables(@Autowired DataSource dataSource) throws SQLException {
    // The context (and so the migration) is up: the DataSource parameter forced it.
    assertThat(dataSource).isNotNull();
    try (Connection owner = TestDatabase.connectAsOwner(DATABASE);
        Statement statement = owner.createStatement()) {
      statement.execute(
          "CREATE TABLE common.roles_probe (id bigserial PRIMARY KEY, label text NOT NULL)");
      statement.execute("CREATE SCHEMA other_module");
      statement.execute("GRANT USAGE ON SCHEMA other_module TO " + TestDatabase.APP_USER);
      statement.execute(
          "CREATE TABLE other_module.roles_probe (id bigserial PRIMARY KEY, label text NOT NULL)");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"common.roles_probe", "other_module.roles_probe"})
  void theAppReadsAndWritesOwnerCreatedTables(String table) throws SQLException {
    try (Connection app = TestDatabase.connectAsApp(DATABASE);
        Statement statement = app.createStatement()) {
      statement.executeUpdate("INSERT INTO " + table + " (label) VALUES ('first')");
      statement.executeUpdate("UPDATE " + table + " SET label = 'second' WHERE label = 'first'");
      try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isPositive();
      }
      statement.executeUpdate("DELETE FROM " + table + " WHERE label = 'second'");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE common.app_table (id int)",
        "CREATE TABLE public.app_table (id int)",
        "CREATE TABLE other_module.app_table (id int)",
        "CREATE SCHEMA app_schema",
        "ALTER TABLE common.roles_probe ADD COLUMN extra int",
        "DROP TABLE common.roles_probe",
        "ALTER TABLE other_module.roles_probe ADD COLUMN extra int",
        "DROP TABLE other_module.roles_probe",
        "INSERT INTO common.flyway_schema_history (installed_rank, description, type, script,"
            + " installed_by, execution_time, success) VALUES (999, 'x', 'SQL', 'x', 'x', 0, true)"
      })
  void theAppRunsNoDdlAndCannotWriteTheHistory(String sql) throws SQLException {
    try (Connection app = TestDatabase.connectAsApp(DATABASE);
        Statement statement = app.createStatement()) {
      assertThatThrownBy(() -> statement.execute(sql))
          .isInstanceOf(SQLException.class)
          .extracting(ex -> ((SQLException) ex).getSQLState())
          .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }
  }

  @Test
  void theAppReadsTheSchemaHistory() {
    Long applied =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT count(*) FROM common.flyway_schema_history WHERE success", Long.class);

    assertThat(applied).isPositive();
  }

  /** The roles script's own database: the app creates nothing in the public schema either. */
  @Test
  void theAppCannotCreateTablesInTheSharedDatabase() throws SQLException {
    try (Connection app = TestDatabase.connectAsApp(TestDatabase.SHARED_DATABASE)) {
      app.setAutoCommit(false);
      try (Statement statement = app.createStatement()) {
        assertThatThrownBy(() -> statement.execute("CREATE TABLE public.app_table (id int)"))
            .isInstanceOf(SQLException.class)
            .extracting(ex -> ((SQLException) ex).getSQLState())
            .isEqualTo(INSUFFICIENT_PRIVILEGE);
      } finally {
        app.rollback();
      }
    }
  }

  @Test
  void theAppConnectsAsTheAppUserWithTheStatementTimeout() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject("SELECT current_user", String.class))
        .isEqualTo(TestDatabase.APP_USER);
    assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("15s");
  }
}
