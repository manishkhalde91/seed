package com.company.seed.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The one-off {@code migrate} run (data-and-migrations.md §11): Flyway has migrated during startup
 * (a failed migration fails startup, so the process exits 1); once startup has succeeded this
 * closes the context, before any other runner, and with no web server the process exits 0. If an
 * override turned Flyway off, nothing was migrated, so the run fails (exit 1) instead.
 */
@Component
@Profile("migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
class MigrateRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(MigrateRunner.class);

  private final ConfigurableApplicationContext context;
  private final ObjectProvider<Flyway> flyway;

  MigrateRunner(ConfigurableApplicationContext context, ObjectProvider<Flyway> flyway) {
    this.context = context;
    this.flyway = flyway;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (flyway.getIfAvailable() == null) {
      throw new IllegalStateException(
          "The migrate profile ran with Flyway disabled (spring.flyway.enabled=false):"
              + " nothing was migrated");
    }
    LOG.info("Database migration complete; closing the application context");
    context.close();
  }
}
