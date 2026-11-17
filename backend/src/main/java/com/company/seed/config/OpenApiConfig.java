package com.company.seed.config;

import com.company.seed.common.api.ApiErrorResponse;
import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.FieldError;
import com.company.seed.common.api.PageMeta;
import com.company.seed.common.api.PageQuery;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The OpenAPI document that springdoc serves and the build exports to {@code
 * backend/openapi/api.yaml} (api-standards.md §7). The server URL is fixed to {@code /}, otherwise
 * springdoc writes {@code http://localhost:<port>} and every run would show drift; the envelope
 * schemas are registered even before an endpoint references them.
 *
 * <p>Every operation requires a bearer JWT ({@value #BEARER_SCHEME}, the global security
 * requirement). In {@code local} and {@code dev}, the served document also offers {@value
 * #OIDC_SCHEME}, the issuer's OpenID Connect sign-in, so Swagger UI's Authorize can obtain a token
 * (authorization code + PKCE, {@code springdoc.swagger-ui.oauth.*}). The exported contract never
 * contains it: it depends on the environment's issuer.
 *
 * <p>Every operation is documented with the envelope by {@link EnvelopeOperationCustomizer}; the
 * envelope schemas list their {@code required} members, as the records always write them.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  /** The bearer-JWT security scheme every operation requires. */
  static final String BEARER_SCHEME = "bearer-jwt";

  /** The OpenID Connect sign-in offered to Swagger UI in {@code local} and {@code dev}. */
  static final String OIDC_SCHEME = "oidc";

  private static final List<Class<?>> ENVELOPE_TYPES =
      List.of(ApiResponse.class, ApiErrorResponse.class, FieldError.class, PageMeta.class);

  /**
   * The members each envelope schema always carries. {@code FieldError.field} is written even when
   * {@code null} (an object-level error); {@code rejectedValue} and {@code pagination} are left out
   * when absent.
   */
  private static final Map<Class<?>, List<String>> REQUIRED =
      Map.of(
          ApiResponse.class,
          List.of("success", "message", "data", "timestamp", "correlationId"),
          ApiErrorResponse.class,
          List.of(
              "success",
              "status",
              "message",
              "errorCode",
              "errors",
              "timestamp",
              "path",
              "correlationId"),
          FieldError.class,
          List.of("field", "code", "message"),
          PageMeta.class,
          List.of("page", "size", "totalElements", "totalPages", "hasNext"));

  static {
    // PageQuery is bound from page, size and sort; EnvelopeOperationCustomizer documents those.
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(PageQuery.class);
  }

  @Bean
  OpenAPI seedOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Seed API").version("v1"))
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }

  /**
   * Swagger UI sign-in where the docs are on by default: the issuer's OpenID Connect discovery
   * document, offered as an alternative to pasting a bearer token.
   */
  @Bean
  @Profile({"local", "dev"})
  OpenApiCustomizer swaggerUiSignInCustomizer(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
    return openApi -> {
      if (issuer.isBlank()) {
        return;
      }
      if (openApi.getComponents() == null) {
        openApi.setComponents(new Components());
      }
      openApi
          .getComponents()
          .addSecuritySchemes(
              OIDC_SCHEME,
              new SecurityScheme()
                  .type(SecurityScheme.Type.OPENIDCONNECT)
                  .openIdConnectUrl(
                      issuer.strip().replaceAll("/+$", "") + "/.well-known/openid-configuration"));
      boolean offered =
          openApi.getSecurity() != null
              && openApi.getSecurity().stream()
                  .anyMatch(requirement -> requirement.containsKey(OIDC_SCHEME));
      if (!offered) {
        openApi.addSecurityItem(new SecurityRequirement().addList(OIDC_SCHEME));
      }
    };
  }

  @Bean
  OperationCustomizer envelopeOperationCustomizer() {
    return new EnvelopeOperationCustomizer();
  }

  /** {@code @NotBlank} strings need at least one character: {@code minLength: 1}. */
  @Bean
  ModelConverter notBlankMinLengthConverter() {
    return new NotBlankMinLengthConverter();
  }

  @Bean
  OpenApiCustomizer envelopeSchemasCustomizer() {
    return openApi -> {
      if (openApi.getComponents() == null) {
        openApi.setComponents(new Components());
      }
      Components components = openApi.getComponents();
      ModelConverters converters =
          ModelConverters.getInstance(openApi.getSpecVersion() == SpecVersion.V31);
      for (Class<?> type : ENVELOPE_TYPES) {
        converters
            .readAll(type)
            .forEach(
                (name, schema) -> {
                  if (components.getSchemas() == null
                      || !components.getSchemas().containsKey(name)) {
                    components.addSchemas(name, schema);
                  }
                });
      }
      REQUIRED.forEach(
          (type, required) -> {
            Schema<?> schema = components.getSchemas().get(type.getSimpleName());
            if (schema != null) {
              schema.setRequired(required);
            }
          });
      Schema<?> fieldError = components.getSchemas().get(FieldError.class.getSimpleName());
      if (fieldError != null && fieldError.getProperties().get("field") != null) {
        fieldError
            .getProperties()
            .get("field")
            .setTypes(new LinkedHashSet<>(List.of("string", "null")));
      }
    };
  }
}
