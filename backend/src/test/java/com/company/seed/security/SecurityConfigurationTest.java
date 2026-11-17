package com.company.seed.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.idempotency.IdempotencyFilter;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.limits.RequestSizeFilter;
import com.company.seed.common.limits.WriteRateLimitFilter;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.logging.RequestLoggingFilter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Startup of {@link SecurityConfig} with the real {@code application.yml}: the identity provider's
 * settings come from the environment, and a missing or invalid one stops startup with a message
 * naming the property. No issuer is contacted at startup.
 */
class SecurityConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
          .withUserConfiguration(SecurityConfig.class)
          .withBean(
              ApiErrorResponseWriter.class,
              () -> new ApiErrorResponseWriter(Clock.systemUTC(), JsonMapper.builder().build()))
          .withBean(
              CorrelationIdFilter.class,
              () -> new CorrelationIdFilter(new IdGenerator(Clock.systemUTC())))
          // An issuer nobody listens on: the JWKS is fetched on the first token, never at startup.
          .withPropertyValues(
              "OIDC_ISSUER_URI=http://127.0.0.1:9/seed",
              "OIDC_AUDIENCE=seed-api",
              "CORS_ALLOWED_ORIGINS=http://localhost:4200,https://app.example");

  @Test
  void startsWithoutReachingTheIssuer() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(SecurityFilterChain.class);
          assertThat(context).hasSingleBean(JwtDecoder.class);
          SeedSecurityProperties properties = context.getBean(SeedSecurityProperties.class);
          assertThat(properties.audience()).isEqualTo("seed-api");
          assertThat(properties.rolesClaim()).isEqualTo("roles");
          assertThat(properties.corsAllowedOrigins())
              .containsExactly("http://localhost:4200", "https://app.example");
          assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.oauth.client-id"))
              .isEqualTo("seed-swagger");
        });
  }

  @Test
  void startupFailsWithoutAnAudience() {
    runner
        .withPropertyValues("OIDC_AUDIENCE=")
        .run(context -> assertFailureNames(context.getStartupFailure(), "seed.security.audience"));
  }

  @Test
  void startupFailsWithoutAnIssuer() {
    runner
        .withPropertyValues("OIDC_ISSUER_URI=")
        .run(
            context ->
                assertFailureNames(
                    context.getStartupFailure(),
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "https://*.example",
        "null",
        "localhost:4200",
        "http://localhost:4200/",
        "https://app.example/app",
        "ftp://app.example",
        "https://app.example?x=1"
      })
  void startupFailsOnAnOriginThatIsNotSchemeHostPort(String origin) {
    runner
        .withPropertyValues("CORS_ALLOWED_ORIGINS=" + origin)
        .run(
            context ->
                assertFailureNames(
                    context.getStartupFailure(), "seed.security.cors-allowed-origins"));
  }

  @Test
  void startupFailsWhenTheSecurityChainWouldRunAfterTheIdempotencyFilter() {
    runner
        .withPropertyValues("spring.security.filter.order=-90")
        .run(
            context ->
                assertFailureNames(context.getStartupFailure(), "spring.security.filter.order"));
  }

  @ParameterizedTest(name = "spring.security.filter.order={0}")
  @ValueSource(ints = {-95, -94})
  void startupFailsWhenTheSecurityChainWouldRunAtOrAfterTheWriteRateLimit(int order) {
    runner
        .withPropertyValues("spring.security.filter.order=" + order)
        .run(
            context -> {
              assertFailureNames(context.getStartupFailure(), "spring.security.filter.order");
              assertFailureNames(
                  context.getStartupFailure(),
                  "spring.security.filter.order is "
                      + order
                      + "; it must be lower than WriteRateLimitFilter.ORDER ("
                      + WriteRateLimitFilter.ORDER
                      + ")");
            });
  }

  @Test
  void startsWithTheSecurityChainRightBeforeTheWriteRateLimit() {
    runner
        .withPropertyValues("spring.security.filter.order=" + (WriteRateLimitFilter.ORDER - 1))
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void theFilterOrderIsSecurityThenWriteRateLimitThenIdempotency() {
    assertThat(SecurityFilterProperties.DEFAULT_FILTER_ORDER)
        .isLessThan(WriteRateLimitFilter.ORDER)
        .isEqualTo(-100);
    assertThat(WriteRateLimitFilter.ORDER).isLessThan(IdempotencyFilter.ORDER).isEqualTo(-95);
    assertThatIllegalStateException()
        .isThrownBy(() -> SecurityConfig.requireFilterOrder(-100, -90, -90))
        .withMessageContaining("WriteRateLimitFilter.ORDER (-90)")
        .withMessageContaining("IdempotencyFilter.ORDER (-90)");
    assertThatIllegalStateException()
        .isThrownBy(() -> SecurityConfig.requireFilterOrder(-100, -89, -90))
        .withMessageContaining("WriteRateLimitFilter.ORDER (-89)")
        .withMessageContaining("IdempotencyFilter.ORDER (-90)");
  }

  @Test
  void theBrowserRulesRunAfterTheRequestLogAndBeforeTheBodyLimit() {
    assertThat(SecurityConfig.BROWSER_POLICY_FILTER_ORDER)
        .isGreaterThan(RequestLoggingFilter.ORDER)
        .isLessThan(RequestSizeFilter.ORDER);
    runner.run(
        context ->
            assertThat(context.getBean("browserPolicyFilter", FilterRegistrationBean.class))
                .extracting(FilterRegistrationBean::getOrder)
                .isEqualTo(SecurityConfig.BROWSER_POLICY_FILTER_ORDER));
  }

  @Test
  void startupFailsOnAnEmptyRolesClaimSegment() {
    runner
        .withPropertyValues("OIDC_ROLES_CLAIM=realm_access.")
        .run(
            context ->
                assertFailureNames(context.getStartupFailure(), "seed.security.roles-claim"));
  }

  /** Some exception in the cause chain names the property. */
  private static void assertFailureNames(Throwable failure, String property) {
    assertThat(failure).as("startup failure").isNotNull();
    StringBuilder messages = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    assertThat(messages.toString()).contains(property);
  }
}
