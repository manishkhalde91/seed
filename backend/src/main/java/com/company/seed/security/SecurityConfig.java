package com.company.seed.security;

import static org.springframework.security.config.Customizer.withDefaults;

import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.idempotency.IdempotencyFilter;
import com.company.seed.common.limits.WriteRateLimitFilter;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.logging.RequestLoggingFilter;
import com.company.seed.common.operations.infrastructure.ws.StompSessions;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The API as an OAuth 2.1 / OIDC resource server (security.md §1-§4, §6). One filter chain serves
 * the application port and, through Spring Boot, the management port.
 *
 * <ul>
 *   <li><b>Tokens:</b> RS256 JWTs checked against the issuer's JWKS, found through {@code
 *       OIDC_ISSUER_URI} on first use (startup never needs the issuer); issuer, {@code exp}/{@code
 *       nbf} with {@value #CLOCK_SKEW_SECONDS} s of clock skew, the audience and the subject. Any
 *       failure is a 401; an issuer that cannot be reached is a 503.
 *   <li><b>Roles:</b> {@link JwtRoleConverter}; {@code ADMIN} implies {@code USER}.
 *   <li><b>Deny by default:</b> on the management port health and Prometheus are open (the port is
 *       network-restricted) and every other exposed endpoint needs {@code ADMIN}; on the
 *       application port the API docs are open (they are served only where enabled), so is the
 *       WebSocket handshake of {@code /ws} (its {@code CONNECT} frame carries the token), and
 *       everything else needs a valid token. Every handler method also carries
 *       {@code @PreAuthorize} (ArchUnit-enforced). The container's own ERROR and ASYNC
 *       re-dispatches of an already authorised request pass.
 *   <li><b>Errors:</b> 401 and 403 are the JSON envelope, with the correlation ID (on the
 *       management port too, where this chain runs the correlation filter itself); a 401 carries
 *       {@code WWW-Authenticate: Bearer} and never the reason.
 *   <li><b>Browser rules:</b> CORS for {@code CORS_ALLOWED_ORIGINS} only; no CSRF, sessions,
 *       cookies, login or logout; {@code nosniff}, {@code no-referrer} and {@code frame-ancestors
 *       'none'} on every response. On the application port CORS and these headers come from {@link
 *       BrowserPolicyFilter}, ahead of the request limits.
 * </ul>
 *
 * Only in a servlet web application: the {@code migrate} run starts no web server and needs no
 * identity provider.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@EnableConfigurationProperties(SeedSecurityProperties.class)
class SecurityConfig {

  static final long CLOCK_SKEW_SECONDS = 30;

  static final int MAX_SUBJECT_LENGTH = 255;

  /**
   * Order of {@link BrowserPolicyFilter}: after the request log, before {@code RequestSizeFilter}.
   */
  static final int BROWSER_POLICY_FILTER_ORDER = RequestLoggingFilter.ORDER + 1;

  /** Spring Boot's order of the security filter chain among the servlet filters. */
  static final String FILTER_ORDER_PROPERTY = "spring.security.filter.order";

  /** Spring Boot's issuer property, set from {@code OIDC_ISSUER_URI}. */
  static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

  /** The API docs; springdoc serves them only where enabled ({@code local} and {@code dev}). */
  static final String[] DOCS = {
    "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**"
  };

  /**
   * The STOMP endpoint's handshake: open, because the token travels in the {@code CONNECT} frame,
   * never in the URL; {@code security.ws} authenticates the frame (security.md §4).
   */
  static final String WEBSOCKET = StompSessions.ENDPOINT;

  static final List<String> CORS_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  static final List<String> CORS_HEADERS =
      List.of(
          HttpHeaders.AUTHORIZATION,
          HttpHeaders.CONTENT_TYPE,
          CorrelationIdFilter.HEADER,
          IdempotencyFilter.HEADER);
  static final List<String> CORS_EXPOSED_HEADERS =
      List.of(
          CorrelationIdFilter.HEADER,
          HttpHeaders.LOCATION,
          IdempotencyFilter.REPLAYED_HEADER,
          HttpHeaders.RETRY_AFTER);
  static final Duration CORS_MAX_AGE = Duration.ofHours(1);

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiErrorResponseWriter errorWriter,
      CorrelationIdFilter correlationIdFilter,
      JwtAuthenticationConverter authenticationConverter,
      @Value(
              "${"
                  + FILTER_ORDER_PROPERTY
                  + ":"
                  + SecurityFilterProperties.DEFAULT_FILTER_ORDER
                  + "}")
          int securityFilterOrder) {
    requireFilterOrder(securityFilterOrder, WriteRateLimitFilter.ORDER, IdempotencyFilter.ORDER);
    JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(errorWriter);
    // An issuer that cannot be reached is answered 503 by the entry point, not rethrown.
    AuthenticationEntryPointFailureHandler failureHandler =
        new AuthenticationEntryPointFailureHandler(entryPoint);
    failureHandler.setRethrowAuthenticationServiceException(false);
    JsonAccessDeniedHandler accessDeniedHandler = new JsonAccessDeniedHandler(errorWriter);
    http.csrf(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // The application port gets these headers and CORS from BrowserPolicyFilter, before the
        // request limits; the management port gets only this chain, so it writes the headers too.
        .headers(
            headers ->
                headers
                    .contentTypeOptions(withDefaults())
                    .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                    .contentSecurityPolicy(
                        csp -> csp.policyDirectives(BrowserPolicyFilter.FRAME_ANCESTORS_NONE)))
        .authorizeHttpRequests(
            requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("health"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("prometheus"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .hasRole(Roles.ADMIN)
                    .requestMatchers(DOCS)
                    .permitAll()
                    .requestMatchers(WEBSOCKET)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(authenticationConverter))
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                          @Override
                          public <O extends BearerTokenAuthenticationFilter> O postProcess(
                              O filter) {
                            filter.setAuthenticationFailureHandler(failureHandler);
                            return filter;
                          }
                        }))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        // On the application port the correlation filter has already run (it skips a second pass);
        // on the management port, which gets only this chain, it gives the 401/403 envelopes there
        // their correlation ID.
        .addFilterBefore(correlationIdFilter, DisableEncodeUrlFilter.class)
        .addFilterAfter(new AuthenticatedUserMdcFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /**
   * CORS and the security headers as a servlet filter of the application port, right after the
   * request log and before the request limits ({@code RequestSizeFilter}) and this chain, so every
   * response carries them, the early 413 included.
   */
  @Bean
  FilterRegistrationBean<BrowserPolicyFilter> browserPolicyFilter(
      SeedSecurityProperties properties, ApiErrorResponseWriter errorWriter) {
    FilterRegistrationBean<BrowserPolicyFilter> registration =
        new FilterRegistrationBean<>(
            new BrowserPolicyFilter(
                corsConfigurationSource(properties), new JsonCorsProcessor(errorWriter)));
    registration.setOrder(BROWSER_POLICY_FILTER_ORDER);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    return registration;
  }

  /**
   * Turns a decoded token into the caller: subject as the name, {@link JwtRoleConverter} roles as
   * authorities. Shared by the request chain and the STOMP {@code CONNECT} check, so both accept
   * the same callers.
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter(SeedSecurityProperties properties) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter(properties.rolesClaim()));
    return converter;
  }

  /** {@code ADMIN} implies {@code USER}, for request rules and method security alike. */
  @Bean
  static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.withDefaultRolePrefix().role(Roles.ADMIN).implies(Roles.USER).build();
  }

  /**
   * The checks every token passes after its signature: an {@code exp} (required) and {@code nbf}
   * within the clock skew, the issuer, the audience, and a {@code sub} fit to name the caller (see
   * {@link #isValidSubject}). A bean of its own, so tests decode their tokens with exactly these
   * rules.
   */
  @Bean
  OAuth2TokenValidator<Jwt> jwtValidator(
      @Value("${" + ISSUER_PROPERTY + ":}") String issuer, SeedSecurityProperties properties) {
    JwtTimestampValidator timestamps =
        new JwtTimestampValidator(Duration.ofSeconds(CLOCK_SKEW_SECONDS));
    timestamps.setAllowEmptyExpiryClaim(false);
    return new DelegatingOAuth2TokenValidator<>(
        timestamps,
        new JwtIssuerValidator(requireIssuer(issuer)),
        new JwtAudienceValidator(properties.audience()),
        new JwtClaimValidator<Object>(
            JwtClaimNames.SUB, sub -> sub instanceof String text && isValidSubject(text)));
  }

  /**
   * A subject becomes the caller ({@code user:<sub>}) in audit columns, idempotency records and log
   * lines, so it must name one caller: not blank, at most {@value #MAX_SUBJECT_LENGTH} characters,
   * no whitespace or control characters (the rule of {@code SystemActor} names).
   */
  static boolean isValidSubject(String subject) {
    return !subject.isEmpty()
        && subject.length() <= MAX_SUBJECT_LENGTH
        && subject
            .codePoints()
            .noneMatch(
                c ->
                    Character.isWhitespace(c)
                        || Character.isSpaceChar(c)
                        || Character.isISOControl(c));
  }

  /**
   * The security chain, then {@code WriteRateLimitFilter}, then {@code IdempotencyFilter}: the rate
   * limit needs the authenticated caller, and must see every write before idempotency, so replays
   * count; idempotency needs the caller too, so that replays need a token and records are scoped to
   * the caller. Any other order fails startup, naming the orders involved.
   */
  static void requireFilterOrder(
      int securityFilterOrder, int writeRateLimitOrder, int idempotencyOrder) {
    if (securityFilterOrder >= writeRateLimitOrder) {
      throw new IllegalStateException(
          FILTER_ORDER_PROPERTY
              + " is "
              + securityFilterOrder
              + "; it must be lower than WriteRateLimitFilter.ORDER ("
              + writeRateLimitOrder
              + "), so that requests are authenticated before their caller's write rate is checked");
    }
    if (writeRateLimitOrder >= idempotencyOrder) {
      throw new IllegalStateException(
          "WriteRateLimitFilter.ORDER ("
              + writeRateLimitOrder
              + ") must be lower than IdempotencyFilter.ORDER ("
              + idempotencyOrder
              + "), so that replayed writes count against the write rate");
    }
  }

  /**
   * Verifies signatures with the issuer's JWKS. The issuer's metadata is fetched on the first
   * token, not at startup; a failed fetch is retried with the next token.
   */
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${" + ISSUER_PROPERTY + ":}") String issuer, OAuth2TokenValidator<Jwt> jwtValidator) {
    String location = requireIssuer(issuer);
    JwtDecoder decoder =
        new SupplierJwtDecoder(
            () -> {
              NimbusJwtDecoder nimbus = NimbusJwtDecoder.withIssuerLocation(location).build();
              nimbus.setJwtValidator(jwtValidator);
              return nimbus;
            });
    // An unreachable issuer is a JwtException, so the token is "not checkable" (503), not "bad".
    return token -> {
      try {
        return decoder.decode(token);
      } catch (JwtDecoderInitializationException ex) {
        throw new JwtException("The issuer's metadata could not be fetched", ex);
      }
    };
  }

  static String requireIssuer(String issuer) {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalStateException(
          ISSUER_PROPERTY
              + " (OIDC_ISSUER_URI) must be set: the OIDC issuer whose tokens the API accepts");
    }
    return issuer.strip();
  }

  static CorsConfigurationSource corsConfigurationSource(SeedSecurityProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.corsAllowedOrigins());
    cors.setAllowedMethods(CORS_METHODS);
    cors.setAllowedHeaders(CORS_HEADERS);
    cors.setExposedHeaders(CORS_EXPOSED_HEADERS);
    cors.setMaxAge(CORS_MAX_AGE);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return source;
  }
}
