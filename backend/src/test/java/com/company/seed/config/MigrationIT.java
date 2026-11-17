package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.seed.SeedApplication;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The main migrations on a fresh database (data-and-migrations.md §11), and the one-off {@code
 * migrate} profile run as its own process, as an operator would start it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = TestDatabase.DATABASE_PROPERTY + "=" + MigrationIT.FRESH)
@Import(TestDatabase.class)
class MigrationIT {

  static final String FRESH = "migration_fresh";
  private static final String MIGRATE_PROFILE = "migration_profile";
  private static final String[] COMMON_VERSIONS = {
    "202611160900", "202611161100", "202611161600", "202611171000", "202611171005"
  };
  private static final long PROCESS_TIMEOUT_SECONDS = 180;

  @Autowired private Flyway flyway;

  @Test
  void freshMigrateCreatesTheCommonSchemaAndHistoryOwnedByTheOwner() throws SQLException {
    try (Connection connection = TestDatabase.connectAsSuperuser(FRESH)) {
      assertThat(
              single(
                  connection,
                  "SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'common'"))
          .isEqualTo(TestDatabase.OWNER_USER);
      assertThat(
              single(
                  connection,
                  "SELECT tableowner FROM pg_tables"
                      + " WHERE schemaname = 'common' AND tablename = 'flyway_schema_history'"))
          .isEqualTo(TestDatabase.OWNER_USER);
      assertThat(
              single(
                  connection,
                  "SELECT tableowner FROM pg_tables"
                      + " WHERE schemaname = 'common' AND tablename = 'idempotency_record'"))
          .isEqualTo(TestDatabase.OWNER_USER);
      assertThat(appliedVersions(connection))
          .contains(COMMON_VERSIONS)
          .containsExactlyElementsOf(mainVersions());
    }
  }

  @Test
  void validatePassesOnTheMigratedDatabase() {
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void migrateProfileMigratesClosesTheContextAndExitsZero(@TempDir Path logs)
      throws IOException, InterruptedException, SQLException {
    TestDatabase.createDatabase(MIGRATE_PROFILE);

    Run run = migrate(MIGRATE_PROFILE, logs.resolve("migrate.log"));

    assertThat(run.exitCode()).as(run.output()).isZero();
    assertThat(run.output())
        .contains("Database migration complete")
        .doesNotContain("Tomcat started")
        .doesNotContain("Tomcat initialized");
    try (Connection connection = TestDatabase.connectAsOwner(MIGRATE_PROFILE)) {
      assertThat(appliedVersions(connection))
          .contains(COMMON_VERSIONS)
          .containsExactlyElementsOf(mainVersions());
    }
  }

  /**
   * The {@code migrate} run only migrates and exits: no {@code @Scheduled} processing, so no
   * scheduler and no job. Any other profile schedules jobs.
   */
  @Test
  void migrateProfileHasNoScheduler() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    runner
        .withPropertyValues("spring.profiles.active=migrate")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    runner.run(
        context ->
            assertThat(context)
                .hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
  }

  /**
   * The {@code migrate} run resubmits no outstanding event publication: an incomplete one left by
   * the previous version is still incomplete afterwards (had its listener run, it would have found
   * no operation and completed the publication).
   */
  @Test
  void migrateProfileLeavesOutstandingEventPublicationsAlone(@TempDir Path logs)
      throws IOException, InterruptedException, SQLException {
    String database = MIGRATE_PROFILE + "_outstanding";
    TestDatabase.createDatabase(database);
    assertThat(migrate(database, logs.resolve("first.log")).exitCode()).isZero();
    String publication = "01934b6f-0000-7000-8000-0000000000aa";
    try (Connection connection = TestDatabase.connectAsApp(database);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO common.event_publication (id, listener_id, event_type, serialized_event,"
              + " publication_date, status, completion_attempts) VALUES ('"
              + publication
              + "', 'com.company.seed.feature.sample.application.SampleExportListener.on("
              + "com.company.seed.feature.sample.SampleExportRequested)',"
              + " 'com.company.seed.feature.sample.SampleExportRequested',"
              + " '{\"operationId\":\"01934b6f-0000-7000-8000-0000000000bb\","
              + "\"requestedBy\":\"user:u\",\"correlationId\":\"c\"}',"
              + " now() - interval '1 hour', 'PUBLISHED', 1)");
    }

    Run run = migrate(database, logs.resolve("second.log"));

    assertThat(run.exitCode()).as(run.output()).isZero();
    // No scheduler at all: the staleness monitor would otherwise mark the row failed.
    assertThat(run.output()).doesNotContain("StalenessMonitorConfiguration");
    try (Connection connection = TestDatabase.connectAsOwner(database)) {
      assertThat(
              single(
                  connection,
                  // Resubmission marks the row synchronously; its async listener could still be
                  // running when the migrate run closes, so completion alone proves nothing.
                  "SELECT count(*) FROM common.event_publication WHERE id = '"
                      + publication
                      + "' AND completion_date IS NULL AND status = 'PUBLISHED'"
                      + " AND last_resubmission_date IS NULL AND completion_attempts = 1"))
          .isEqualTo("1");
    }
  }

  @Test
  void migrateProfileFailsStartupWithExitCodeOneOnABrokenMigration(@TempDir Path directory)
      throws IOException, InterruptedException, SQLException {
    String database = MIGRATE_PROFILE + "_broken";
    TestDatabase.createDatabase(database);
    Path broken = Files.createDirectories(directory.resolve("broken"));
    Files.writeString(broken.resolve("V209901010001__fixture_broken.sql"), "THIS IS NOT SQL;\n");

    Run run =
        migrate(
            database,
            directory.resolve("broken.log"),
            "--spring.flyway.locations=classpath:db/migration/common,filesystem:" + broken);

    assertThat(run.exitCode()).as(run.output()).isEqualTo(1);
    assertThat(run.output()).contains("V209901010001__fixture_broken.sql").contains("flyway");
    try (Connection connection = TestDatabase.connectAsOwner(database)) {
      assertThat(appliedVersions(connection)).containsExactly(COMMON_VERSIONS);
    }
  }

  @Test
  void migrateProfileFailsWhenAnOverrideTurnsFlywayOff(@TempDir Path logs)
      throws IOException, InterruptedException, SQLException {
    String database = MIGRATE_PROFILE + "_disabled";
    TestDatabase.createDatabase(database);

    // Hibernate's schema validation would fail first on the empty database (the idempotency table
    // is missing); turning it off here leaves MigrateRunner's guard as the only check under test.
    Run run =
        migrate(
            database,
            logs.resolve("disabled.log"),
            Map.of("SPRING_FLYWAY_ENABLED", "false"),
            "--spring.jpa.hibernate.ddl-auto=none");

    assertThat(run.exitCode()).as(run.output()).isEqualTo(1);
    assertThat(run.output())
        .contains("Flyway disabled")
        .doesNotContain("Database migration complete");
    try (Connection connection = TestDatabase.connectAsSuperuser(database)) {
      assertThat(single(connection, "SELECT count(*) FROM pg_namespace WHERE nspname = 'common'"))
          .isEqualTo("0");
    }
  }

  private record Run(int exitCode, String output) {}

  private static Run migrate(String database, Path log, String... arguments)
      throws IOException, InterruptedException {
    return migrate(database, log, Map.of(), arguments);
  }

  /**
   * Starts the application with the {@code migrate} profile in a new JVM, configured only through
   * the {@code DB_*} and {@code DB_OWNER_*} variables. Only the main classes and resources are on
   * its classpath.
   */
  private static Run migrate(
      String database, Path log, Map<String, String> extraEnvironment, String... arguments)
      throws IOException, InterruptedException {
    String java = ProcessHandle.current().info().command().orElseThrow();
    String classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> !isTestOutput(Path.of(entry)))
            .collect(Collectors.joining(File.pathSeparator));
    List<String> command = new ArrayList<>();
    command.addAll(
        List.of(
            java,
            "-Duser.timezone=UTC",
            "-cp",
            classpath,
            SeedApplication.class.getName(),
            "--spring.profiles.active=migrate"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
    Map<String, String> environment = builder.environment();
    environment
        .keySet()
        .removeIf(
            name ->
                name.startsWith("DB_")
                    || name.startsWith("SPRING_")
                    || name.equals("SEED_API_DOCS_ENABLED"));
    PostgreSQLContainer container = TestDatabase.container();
    environment.put("DB_HOST", container.getHost());
    environment.put(
        "DB_PORT", String.valueOf(container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
    environment.put("DB_NAME", database);
    environment.put("DB_USERNAME", TestDatabase.APP_USER);
    environment.put("DB_PASSWORD", TestDatabase.APP_PASSWORD);
    environment.put("DB_OWNER_USERNAME", TestDatabase.OWNER_USER);
    environment.put("DB_OWNER_PASSWORD", TestDatabase.OWNER_PASSWORD);
    environment.putAll(extraEnvironment);

    Process process = builder.start();
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(
          "The migrate run did not exit:\n" + Files.readString(log, StandardCharsets.UTF_8));
    }
    return new Run(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
  }

  /**
   * Gradle's compiled test classes and test resources: {@code build/{classes/java,resources}/test}.
   */
  private static boolean isTestOutput(Path entry) {
    return entry.endsWith(Path.of("classes", "java", "test"))
        || entry.endsWith(Path.of("resources", "test"));
  }

  /**
   * Every versioned migration of the main locations ({@code common} and each feature module), in
   * version order, so this test needs no change when a module is added or removed.
   */
  private List<String> mainVersions() {
    return Arrays.stream(flyway.info().all())
        .filter(migration -> migration.getVersion() != null)
        .map(migration -> migration.getVersion().getVersion())
        .toList();
  }

  /** The successfully applied versioned migrations, in order. */
  private static List<String> appliedVersions(Connection connection) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT version FROM common.flyway_schema_history"
                    + " WHERE version IS NOT NULL AND success ORDER BY installed_rank")) {
      while (rows.next()) {
        versions.add(rows.getString(1));
      }
    }
    return versions;
  }

  private static String single(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).as(sql).isTrue();
      return rows.getString(1);
    }
  }
}
