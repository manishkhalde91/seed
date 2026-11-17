package com.company.seed.common.error.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.ProbeController;
import com.company.fixtures.web.ProbeFilter;
import com.company.fixtures.web.ProbeMethodValidationController;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The envelope contract against the running application (real Tomcat, real Jackson): every probe
 * route returns exactly the documented key set of the success or the error shape, including
 * failures outside Spring MVC that reach {@code /error}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  TestDatabase.class,
  TestSecurity.class,
  ProbeController.class,
  ProbeMethodValidationController.class,
  EnvelopeContractIT.ProbeFilterConfig.class
})
class EnvelopeContractIT {

  private static final Set<String> SUCCESS_KEYS =
      Set.of("success", "message", "data", "timestamp", "correlationId");
  private static final Set<String> PAGED_KEYS =
      Set.of("success", "message", "data", "timestamp", "correlationId", "pagination");
  private static final Set<String> ERROR_KEYS =
      Set.of(
          "success",
          "status",
          "message",
          "errorCode",
          "errors",
          "timestamp",
          "path",
          "correlationId");

  @TestConfiguration
  static class ProbeFilterConfig {
    @Bean
    ProbeFilter probeFilter(ApiErrorResponseWriter writer) {
      return new ProbeFilter(writer);
    }
  }

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  private RestClient client;

  @BeforeEach
  void createClient() {
    client = LenientRestClient.forPort(port);
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "GET,    /probe/ok,          200",
    "POST,   /probe/created,     201",
    "POST,   /probe/accepted,    202",
    "DELETE, /probe/things/1,    200",
    "GET,    /probe/items/5,     200",
    "GET,    /probe/page,        200",
    "GET,    /probe/filter?count=3, 200"
  })
  void successShape(String method, String path, int status) {
    ResponseEntity<String> response = call(method, path, null, null);

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    Map<String, Object> body = parse(response);
    assertThat(body.keySet()).isEqualTo(path.equals("/probe/page") ? PAGED_KEYS : SUCCESS_KEYS);
    assertThat(body).containsEntry("success", true);
    assertThat(Instant.parse((String) body.get("timestamp"))).isNotNull();
  }

  @ParameterizedTest(name = "{0} {1} -> {4} {5}")
  @CsvSource({
    "POST,   /probe/items,               application/json, '{\"name\": \"\"}', 400, VALIDATION_FAILED,        /probe/items",
    "GET,    /probe/search?size=0,       ,                 ,                    400, VALIDATION_FAILED,        /probe/search",
    "GET,    /probe-mv/search?size=0,    ,                 ,                    400, VALIDATION_FAILED,        /probe-mv/search",
    "POST,   /probe/items,               application/json, '{not json',         400, MALFORMED_REQUEST,        /probe/items",
    "GET,    /probe/count?count=abc,     ,                 ,                    400, MALFORMED_REQUEST,        /probe/count",
    "GET,    /no/such/route,             ,                 ,                    404, NOT_FOUND,                /no/such/route",
    "GET,    /probe/throw/not-found,     ,                 ,                    404, THING_NOT_FOUND,          /probe/throw/not-found",
    "DELETE, /probe/ok,                  ,                 ,                    405, METHOD_NOT_ALLOWED,       /probe/ok",
    "POST,   /probe/items,               text/plain,       hello,               415, UNSUPPORTED_MEDIA_TYPE,   /probe/items",
    "GET,    /probe/throw/conflict,      ,                 ,                    409, THING_ALREADY_EXISTS,     /probe/throw/conflict",
    "GET,    /probe/throw/business,      ,                 ,                    422, THING_LIMIT_REACHED,      /probe/throw/business",
    "GET,    /probe/throw/forbidden,     ,                 ,                    403, FORBIDDEN,                /probe/throw/forbidden",
    "GET,    /probe/throw/unavailable,   ,                 ,                    503, SERVICE_UNAVAILABLE,      /probe/throw/unavailable",
    "GET,    /probe/throw/too-large,     ,                 ,                    413, PAYLOAD_TOO_LARGE,        /probe/throw/too-large",
    "GET,    /probe/throw/boom,          ,                 ,                    500, INTERNAL_ERROR,           /probe/throw/boom",
    "GET,    /probe-filter/throws,       ,                 ,                    500, INTERNAL_ERROR,           /probe-filter/throws",
    "GET,    /probe-filter/send-error,   ,                 ,                    403, FORBIDDEN,                /probe-filter/send-error",
    "GET,    /probe-filter/writer,       ,                 ,                    429, RATE_LIMITED,             /probe-filter/writer",
    "PUT,    /probe/items/7,             application/json, '{\"name\": \"ok\"}', 400, VALIDATION_FAILED,        /probe/items/7",
    "PUT,    /probe-mv/items/0,          application/json, '{\"name\": \"\"}',   400, VALIDATION_FAILED,        /probe-mv/items/0",
    "POST,   /probe-mv/items,            application/json, '[{\"name\": \"\"}]', 400, VALIDATION_FAILED,       /probe-mv/items",
    "GET,    /probe/filter?count=abc,    ,                 ,                    400, VALIDATION_FAILED,        /probe/filter",
    "GET,    /probe/header,              ,                 ,                    400, MALFORMED_REQUEST,        /probe/header",
    "GET,    /probe/throw/bad-gateway,   ,                 ,                    502, EXTERNAL_SERVICE_ERROR,   /probe/throw/bad-gateway",
    "GET,    /probe/throw/timeout,       ,                 ,                    504, EXTERNAL_SERVICE_TIMEOUT, /probe/throw/timeout",
    "GET,    /probe-filter/seed,         ,                 ,                    409, THING_ALREADY_EXISTS,     /probe-filter/seed",
    "GET,    /error,                     ,                 ,                    404, NOT_FOUND,                /error"
  })
  void errorShape(
      String method,
      String path,
      String contentType,
      String requestBody,
      int status,
      String errorCode,
      String expectedPath) {
    ResponseEntity<String> response = call(method, path, contentType, requestBody);

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
        .isTrue();
    assertThat(response.getBody())
        .doesNotContain("secret_table")
        .doesNotContain("Exception")
        .doesNotContain("\tat ");
    Map<String, Object> body = parse(response);
    assertThat(body.keySet()).isEqualTo(ERROR_KEYS);
    assertThat(body)
        .containsEntry("success", false)
        .containsEntry("status", status)
        .containsEntry("errorCode", errorCode)
        .containsEntry("path", expectedPath);
    assertThat(body.get("errors")).isInstanceOf(List.class);
  }

  private ResponseEntity<String> call(
      String method, String path, String contentType, String requestBody) {
    RestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(method)).uri(path);
    if (contentType != null) {
      request.contentType(MediaType.parseMediaType(contentType)).body(requestBody);
    }
    return request.retrieve().toEntity(String.class);
  }

  private Map<String, Object> parse(ResponseEntity<String> response) {
    assertThat(response.getBody()).isNotBlank();
    return jsonMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
  }
}
