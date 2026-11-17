package com.company.seed.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The {@code schemaVersion} readiness contributor (data-and-migrations.md §11): DOWN while the
 * database is behind the migrations on the classpath, so a new version takes no traffic before the
 * {@code migrate} run has happened.
 *
 * <p>It is DOWN when a classpath migration is not applied (pending, or ignored because it is older
 * than the latest applied one), when the history holds a failed migration, or when the history
 * cannot be read at all; the details name the versions. It reads the history through a read-only
 * Flyway on the application's own data source, with the locations, schemas and table of {@code
 * spring.flyway.*}, so it works where Flyway's own run is disabled and the application holds no DDL
 * rights. It never migrates and never throws.
 *
 * <p>Once it has reported UP it stays UP without querying again: the migrations of this image
 * cannot become un-applied. While DOWN it re-checks on every probe.
 */
@Component
class SchemaVersionHealthIndicator implements HealthIndicator {

  private final Flyway flyway;
  private volatile Health up;

  SchemaVersionHealthIndicator(DataSource dataSource, Environment environment) {
    FlywayProperties properties =
        Binder.get(environment)
            .bind("spring.flyway", FlywayProperties.class)
            .orElseGet(FlywayProperties::new);
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(properties.getLocations().toArray(String[]::new))
            .table(properties.getTable());
    if (!properties.getSchemas().isEmpty()) {
      configuration.schemas(properties.getSchemas().toArray(String[]::new));
    }
    if (properties.getDefaultSchema() != null) {
      configuration.defaultSchema(properties.getDefaultSchema());
    }
    this.flyway = configuration.load();
  }

  @Override
  public Health health() {
    Health cached = up;
    if (cached != null) {
      return cached;
    }
    try {
      Health health = check(flyway.info());
      if (Status.UP.equals(health.getStatus())) {
        up = health;
      }
      return health;
    } catch (RuntimeException ex) {
      return Health.down()
          .withDetail("error", "Schema history unreadable (" + ex.getClass().getSimpleName() + ")")
          .build();
    }
  }

  private static Health check(MigrationInfoService info) {
    List<String> pending = versions(Arrays.stream(info.pending()));
    List<String> ignored =
        versions(Arrays.stream(info.all()).filter(m -> m.getState() == MigrationState.IGNORED));
    List<String> failed = versions(Arrays.stream(info.all()).filter(m -> m.getState().isFailed()));
    Health.Builder health =
        pending.isEmpty() && ignored.isEmpty() && failed.isEmpty() ? Health.up() : Health.down();
    MigrationInfo current = info.current();
    health.withDetail(
        "current",
        current == null || current.getVersion() == null ? "none" : current.getVersion().toString());
    if (!pending.isEmpty()) {
      health.withDetail("pendingCount", pending.size()).withDetail("pending", pending);
    }
    if (!ignored.isEmpty()) {
      health.withDetail("ignored", ignored);
    }
    if (!failed.isEmpty()) {
      health.withDetail("failed", failed);
    }
    return health.build();
  }

  private static List<String> versions(Stream<MigrationInfo> migrations) {
    return migrations
        .map(m -> m.getVersion() != null ? m.getVersion().toString() : m.getDescription())
        .toList();
  }
}
