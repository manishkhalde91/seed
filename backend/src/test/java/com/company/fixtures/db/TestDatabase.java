package com.company.fixtures.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * The database of every {@code @SpringBootTest}: one PostgreSQL container per JVM, started from the
 * image and digest of {@code infrastructure/compose.yaml} and initialised by the real {@code
 * infrastructure/postgres/init/01-roles.sh}, so the role separation is the same as locally. The
 * application connects as {@code seed_app}; Flyway runs on startup as {@code seed_owner}.
 *
 * <p>The registrar supplies the {@code DB_*} variables that {@code application.yml} reads, so the
 * production data source settings (statement timeout included) are the ones under test. Flyway runs
 * on startup because {@code src/test/resources/config/application.yml} enables it (a registrar runs
 * too late for Flyway's auto-configuration condition); a test that mirrors a shared environment
 * sets {@code spring.flyway.enabled=false}.
 *
 * <p>The test property {@code seed.test.database} names the database a context uses (default {@code
 * seed_db}). The shared {@code seed_db} only ever receives the main migrations; a test that changes
 * migration history or adds fixture schemas names its own throwaway database, created on first use.
 *
 * <p>Passwords here are test-only.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  public static final String SHARED_DATABASE = "seed_db";
  public static final String APP_USER = "seed_app";
  public static final String APP_PASSWORD = "seed-app-test-password";
  public static final String OWNER_USER = "seed_owner";
  public static final String OWNER_PASSWORD = "seed-owner-test-password";

  /** The test property naming the database a context uses. */
  public static final String DATABASE_PROPERTY = "seed.test.database";

  /** The JDBC URL options {@code application.yml} sets (with {@code DB_SSLMODE} unset). */
  public static final String URL_OPTIONS =
      "?options=-c%20statement_timeout=15s&sslmode=prefer&logServerErrorDetail=false";

  private static final String SUPERUSER = "postgres";
  private static final String SUPERUSER_PASSWORD = "postgres-test-password";
  private static final Path INFRASTRUCTURE = infrastructure();
  private static final Pattern POSTGRES_IMAGE =
      Pattern.compile("image:\\s*(\\S*postgres):[^@\\s]+@(sha256:[0-9a-f]{64})");

  private static final PostgreSQLContainer SHARED = start(newContainer());
  private static final Set<String> CREATED = ConcurrentHashMap.newKeySet(16);

  /** The main configuration; the test overrides live in {@code config/application.yml}. */
  private static final String MAIN_CONFIGURATION = "/application.yml";

  static {
    CREATED.add(SHARED_DATABASE);
  }

  @Bean
  DynamicPropertyRegistrar testDatabaseProperties(Environment environment) {
    String database = environment.getProperty(DATABASE_PROPERTY, SHARED_DATABASE);
    return registry -> {
      createDatabase(database);
      registry.add("DB_HOST", SHARED::getHost);
      registry.add("DB_PORT", () -> SHARED.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
      registry.add("DB_NAME", () -> database);
      registry.add("DB_USERNAME", () -> APP_USER);
      registry.add("DB_PASSWORD", () -> APP_PASSWORD);
      registry.add("DB_OWNER_USERNAME", () -> OWNER_USER);
      registry.add("DB_OWNER_PASSWORD", () -> OWNER_PASSWORD);
      registry.add("DB_SSLMODE", () -> "prefer");
    };
  }

  /**
   * A new, not yet started container like the shared one, for a test that must stop its database.
   */
  public static PostgreSQLContainer newContainer() {
    String compose = read(INFRASTRUCTURE.resolve("compose.yaml"));
    Matcher image = POSTGRES_IMAGE.matcher(compose);
    if (!image.find()) {
      throw new IllegalStateException("No pinned postgres image in infrastructure/compose.yaml");
    }
    DockerImageName name =
        DockerImageName.parse(image.group(1) + "@" + image.group(2))
            .asCompatibleSubstituteFor("postgres");
    return new PostgreSQLContainer(name)
        .withDatabaseName(SUPERUSER)
        .withUsername(SUPERUSER)
        .withPassword(SUPERUSER_PASSWORD)
        .withEnv(
            Map.of(
                "DB_NAME", SHARED_DATABASE,
                "DB_OWNER_USERNAME", OWNER_USER,
                "DB_OWNER_PASSWORD", OWNER_PASSWORD,
                "DB_USERNAME", APP_USER,
                "DB_PASSWORD", APP_PASSWORD))
        .withCopyFileToContainer(
            MountableFile.forHostPath(INFRASTRUCTURE.resolve("postgres/init/01-roles.sh"), 0755),
            "/docker-entrypoint-initdb.d/01-roles.sh")
        // Every cached test context keeps a fixed-size pool of 10 (data-and-migrations.md §9).
        .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");
  }

  public static PostgreSQLContainer container() {
    return SHARED;
  }

  /** The JDBC URL of a database in the shared container, with the production URL options. */
  public static String jdbcUrl(String database) {
    return jdbcUrl(SHARED, database);
  }

  /** The JDBC URL of a database in {@code container}, with the production URL options. */
  public static String jdbcUrl(PostgreSQLContainer container, String database) {
    return "jdbc:postgresql://%s:%d/%s%s"
        .formatted(
            container.getHost(),
            container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            database,
            URL_OPTIONS);
  }

  /**
   * Creates a throwaway database in the shared container, once per JVM: owned by {@code
   * seed_owner}, with {@code CONNECT} for {@code seed_app}, as {@code 01-roles.sh} sets up {@code
   * seed_db}.
   */
  public static void createDatabase(String database) {
    if (!database.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("Not a plain database name: " + database);
    }
    synchronized (CREATED) {
      if (CREATED.contains(database)) {
        return;
      }
      try (Connection connection = connectAsSuperuser(SUPERUSER);
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE %s OWNER %s".formatted(database, OWNER_USER));
        statement.execute("REVOKE ALL ON DATABASE %s FROM PUBLIC".formatted(database));
        statement.execute("GRANT CONNECT ON DATABASE %s TO %s".formatted(database, APP_USER));
      } catch (SQLException ex) {
        throw new IllegalStateException("Cannot create database " + database, ex);
      }
      CREATED.add(database);
    }
  }

  /**
   * The main {@code spring.flyway.locations}: {@code common} and every feature module's folder, as
   * {@code src/main/resources/application.yml} lists them. A test that needs "the application's
   * schema" uses these rather than naming modules, so adding or removing a module needs no test
   * change.
   */
  public static String[] mainLocations() {
    return mainFlywayList("locations");
  }

  /** The main {@code spring.flyway.schemas}, as {@link #mainLocations()}. */
  public static String[] mainSchemas() {
    return mainFlywayList("schemas");
  }

  /** {@link #mainLocations()} followed by {@code extra}. */
  public static String[] mainLocationsAnd(String... extra) {
    return Stream.concat(Arrays.stream(mainLocations()), Arrays.stream(extra))
        .toArray(String[]::new);
  }

  /**
   * Migrates a throwaway database as {@code seed_owner} with the main settings (and the main
   * schemas), outside any application context (for tests whose context runs with Flyway off).
   */
  public static void migrate(String database, String... locations) {
    createDatabase(database);
    Flyway.configure()
        .dataSource(jdbcUrl(database), OWNER_USER, OWNER_PASSWORD)
        .locations(locations)
        .schemas(mainSchemas())
        .defaultSchema("common")
        .table("flyway_schema_history")
        .createSchemas(true)
        .placeholders(Map.of("app_role", APP_USER))
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  public static Connection connectAsApp(String database) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(database), APP_USER, APP_PASSWORD);
  }

  public static Connection connectAsOwner(String database) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(database), OWNER_USER, OWNER_PASSWORD);
  }

  public static Connection connectAsSuperuser(String database) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(database), SUPERUSER, SUPERUSER_PASSWORD);
  }

  /** A comma-separated {@code spring.flyway.*} list of the main {@code application.yml}. */
  private static String[] mainFlywayList(String key) {
    try (InputStream yaml = TestDatabase.class.getResourceAsStream(MAIN_CONFIGURATION)) {
      if (yaml == null) {
        throw new IllegalStateException(MAIN_CONFIGURATION + " is not on the classpath");
      }
      Map<String, Object> root = new Yaml().load(yaml);
      Object value = nested(root, "spring", "flyway", key);
      if (!(value instanceof String list) || list.isBlank()) {
        throw new IllegalStateException(
            "spring.flyway." + key + " is not a list in " + MAIN_CONFIGURATION);
      }
      return Arrays.stream(list.split(",")).map(String::strip).toArray(String[]::new);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static Object nested(Map<String, Object> root, String... path) {
    Object value = root;
    for (String segment : path) {
      value = value instanceof Map<?, ?> object ? object.get(segment) : null;
    }
    return value;
  }

  private static PostgreSQLContainer start(PostgreSQLContainer container) {
    container.start();
    return container;
  }

  /** {@code infrastructure/}, whether tests run from {@code backend/} (Gradle) or the repo root. */
  private static Path infrastructure() {
    for (Path candidate : List.of(Path.of("infrastructure"), Path.of("..", "infrastructure"))) {
      if (Files.isRegularFile(candidate.resolve("compose.yaml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "infrastructure/compose.yaml not found from " + Path.of("").toAbsolutePath());
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
