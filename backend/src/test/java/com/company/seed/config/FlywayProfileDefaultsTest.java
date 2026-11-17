package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Where Flyway runs, read from the main configuration files only: every test context turns Flyway
 * on (src/test/resources/config/application.yml), so the production defaults are checked here.
 */
class FlywayProfileDefaultsTest {

  @Test
  void flywayIsOffAndHasNoOwnerCredentialsByDefault() throws IOException {
    StandardEnvironment environment = load("application.yml");

    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
    assertThat(environment.containsProperty("spring.flyway.user")).isFalse();
    assertThat(environment.containsProperty("spring.flyway.password")).isFalse();
  }

  @Test
  void localMigratesAsTheOwner() throws IOException {
    StandardEnvironment environment = load("application.yml", "application-local.yml");

    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
    assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("seed_owner");
  }

  @Test
  void migrateMigratesAsTheOwnerWithoutAWebServer() throws IOException {
    StandardEnvironment environment = load("application.yml", "application-migrate.yml");

    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
    assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("seed_owner");
    assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
  }

  /** The files in order, each later one overriding the earlier ones, with no environment. */
  private static StandardEnvironment load(String... files) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    for (String file : files) {
      loader.load(file, new ClassPathResource(file)).forEach(sources::addFirst);
    }
    return environment;
  }
}
