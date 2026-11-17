package com.company.seed.common.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * {@code seed.limits.*}: its defaults, its validation (startup fails on an invalid value) and the
 * environment variables {@code application.yml} reads it, and the container limits, from.
 */
class LimitsPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LimitsProperties.class)
  static class Config {}

  /** Bound alone, without {@code application.yml}. */
  private final ApplicationContextRunner bare =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  /** Bound through the real {@code application.yml} (and the test overrides on top of it). */
  private final ApplicationContextRunner withApplicationYml =
      bare.withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void defaultsAreOneMegabyteAndSixtyWritesPerMinute() {
    bare.run(
        context -> {
          LimitsProperties properties = context.getBean(LimitsProperties.class);
          assertThat(properties.maxBodySize()).isEqualTo(DataSize.ofBytes(1_048_576));
          assertThat(properties.maxBodyBytes()).isEqualTo(1_048_576);
          assertThat(properties.maxBodySizeText()).isEqualTo("1MB");
          assertThat(properties.writesPerMinute()).isEqualTo(60);
        });
  }

  @Test
  void applicationYmlDefaultsTheContainerLimits() {
    withApplicationYml.run(
        context -> {
          assertThat(context.getBean(LimitsProperties.class).maxBodySize())
              .isEqualTo(DataSize.ofMegabytes(1));
          assertThat(context.getEnvironment().getProperty("server.tomcat.max-http-form-post-size"))
              .isEqualTo("1MB");
          assertThat(context.getEnvironment().getProperty("spring.servlet.multipart.max-file-size"))
              .isEqualTo("10MB");
          assertThat(
                  context.getEnvironment().getProperty("spring.servlet.multipart.max-request-size"))
              .isEqualTo("10MB");
        });
  }

  @Test
  void theEnvironmentVariablesSetEveryLimit() {
    withApplicationYml
        .withPropertyValues(
            "SEED_MAX_BODY_SIZE=2KB", "SEED_WRITES_PER_MINUTE=5", "SEED_MAX_UPLOAD_SIZE=1KB")
        // The test overrides set writes-per-minute directly; drop it to see the variable.
        .withPropertyValues("seed.limits.writes-per-minute=${SEED_WRITES_PER_MINUTE}")
        .run(
            context -> {
              LimitsProperties properties = context.getBean(LimitsProperties.class);
              assertThat(properties.maxBodySize()).isEqualTo(DataSize.ofKilobytes(2));
              assertThat(properties.writesPerMinute()).isEqualTo(5);
              assertThat(
                      context.getEnvironment().getProperty("server.tomcat.max-http-form-post-size"))
                  .isEqualTo("2KB");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("spring.servlet.multipart.max-file-size"))
                  .isEqualTo("1KB");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("spring.servlet.multipart.max-request-size"))
                  .isEqualTo("1KB");
            });
  }

  /** The default of the real {@code application.yml} alone, with no variable set. */
  @Test
  void theProductionDefaultIsSixtyWritesPerMinute() throws IOException {
    List<PropertySource<?>> yaml =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    MutablePropertySources sources = new MutablePropertySources();
    yaml.forEach(sources::addLast);
    assertThat(
            sources.stream().anyMatch(source -> source.containsProperty("SEED_WRITES_PER_MINUTE")))
        .isFalse();

    LimitsProperties properties =
        new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
            .bind("seed.limits", LimitsProperties.class)
            .get();

    assertThat(properties.writesPerMinute()).isEqualTo(60);
    assertThat(properties.maxBodySize()).isEqualTo(DataSize.ofMegabytes(1));
  }

  @ParameterizedTest(name = "max-body-size={0}")
  @ValueSource(strings = {"0B", "-1B", "2147483640B", "2GB"})
  void startupFailsOnABodySizeThatIsNotPositiveAndReadable(String size) {
    bare.withPropertyValues("seed.limits.max-body-size=" + size)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("seed.limits.max-body-size must be positive"));
  }

  @ParameterizedTest(name = "writes-per-minute={0}")
  @ValueSource(strings = {"0", "-1"})
  void startupFailsOnAWriteRateBelowOne(String rate) {
    bare.withPropertyValues("seed.limits.writes-per-minute=" + rate)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("seed.limits.writes-per-minute")
                    .hasMessageContaining("must be at least 1"));
  }

  @Test
  void theLargestReadableBodySizeIsAccepted() {
    bare.withPropertyValues("seed.limits.max-body-size=2147483639B")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void directConstructionRejectsInvalidValues() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LimitsProperties(null, 60))
        .withMessageContaining("seed.limits.max-body-size");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new LimitsProperties(
                    DataSize.ofBytes(LimitsProperties.MAX_BODY_SIZE_BOUND + 1), 60))
        .withMessageContaining("seed.limits.max-body-size");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LimitsProperties(DataSize.ofMegabytes(1), 0))
        .withMessageContaining("seed.limits.writes-per-minute");
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"1MB, 1MB", "2KB, 2KB", "1536KB, 1536KB", "1500B, 1500B", "1GB, 1GB", "1025B, 1025B"})
  void theLimitIsNamedAsConfigured(String size, String text) {
    assertThat(new LimitsProperties(DataSize.parse(size), 60).maxBodySizeText()).isEqualTo(text);
  }
}
