package com.company.fixtures.persistence;

import com.company.fixtures.time.MutableClock;
import com.company.seed.common.error.ConstraintErrorMapping;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.api.Location;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Wires the test-only persistence fixtures into a persistence IT: the fixture entity and repository
 * (next to the production packages), the constraint mapping, the probe controller and a mutable
 * clock. Used only through {@link PersistenceTest}, on a throwaway database that also gets the
 * fixture schema and migration.
 */
@TestConfiguration(proxyBeanMethods = false)
@EntityScan(basePackages = {"com.company.seed", "com.company.fixtures.persistence"})
@EnableJpaRepositories(basePackages = {"com.company.seed", "com.company.fixtures.persistence"})
@Import(AuditedThingProbeController.class)
public class PersistenceFixtures {

  public static final Instant START = Instant.parse("2026-11-16T09:00:00Z");
  public static final String UNIQUE_NAME = "uq_audited_thing__name";
  public static final String QUANTITY_CHECK = "ck_audited_thing__quantity_not_negative";
  public static final String ALREADY_EXISTS = "THING_ALREADY_EXISTS";
  public static final String FIXTURE_LOCATION = "classpath:db/migration/fixture";
  public static final String FIXTURE_SCHEMA = "fixture";

  /**
   * The fixture migration and schema on top of the main {@code spring.flyway.*} ones, whichever
   * modules those name.
   */
  @Bean
  FlywayConfigurationCustomizer fixtureMigrations() {
    return configuration ->
        configuration
            .locations(
                Stream.concat(
                        Arrays.stream(configuration.getLocations()),
                        Stream.of(new Location(FIXTURE_LOCATION)))
                    .toArray(Location[]::new))
            .schemas(
                Stream.concat(Arrays.stream(configuration.getSchemas()), Stream.of(FIXTURE_SCHEMA))
                    .toArray(String[]::new));
  }

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(START);
  }

  @Bean
  ConstraintErrorMapping auditedThingConstraints() {
    return ConstraintErrorMapping.of(UNIQUE_NAME, ALREADY_EXISTS);
  }
}
