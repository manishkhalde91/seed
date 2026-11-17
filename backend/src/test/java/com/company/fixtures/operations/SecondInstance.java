package com.company.fixtures.operations;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.seed.SeedApplication;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test-only: another running instance of the application on the operations ITs' database ({@code
 * seed_operations}), as a second replica behind a load balancer would be: its own ports, broker and
 * {@code PgStatusListener}, the same {@code TestJwt} tokens. It starts after the test's own context
 * (which created and migrated the database), resubmits no event publication and runs no job. Close
 * it in the test.
 */
public final class SecondInstance {

  private SecondInstance() {}

  /** Starts the instance. */
  public static ConfigurableApplicationContext start() {
    PostgreSQLContainer database = TestDatabase.container();
    List<String> arguments =
        List.of(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.main.banner-mode=off",
            "--DB_HOST=" + database.getHost(),
            "--DB_PORT=" + database.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            "--DB_NAME=seed_operations",
            "--DB_USERNAME=" + TestDatabase.APP_USER,
            "--DB_PASSWORD=" + TestDatabase.APP_PASSWORD,
            "--DB_OWNER_USERNAME=" + TestDatabase.OWNER_USER,
            "--DB_OWNER_PASSWORD=" + TestDatabase.OWNER_PASSWORD,
            "--DB_SSLMODE=prefer",
            "--spring.modulith.events.republish-outstanding-events-on-restart=false",
            "--seed.idempotency.cleanup-cron=-",
            "--seed.jobs.operation-cleanup-cron=-",
            "--seed.jobs.event-publication-cleanup-cron=-",
            "--seed.jobs.event-publication-resubmit-cron=-");
    return new SpringApplicationBuilder(SeedApplication.class, TestSecurity.class)
        .run(arguments.toArray(String[]::new));
  }

  /** The application port of a started instance. */
  public static int port(ConfigurableApplicationContext instance) {
    return instance.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
  }
}
