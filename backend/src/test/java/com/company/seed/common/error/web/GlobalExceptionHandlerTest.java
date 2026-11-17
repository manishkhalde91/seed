package com.company.seed.common.error.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.fixtures.web.ProbeController;
import com.company.fixtures.web.ProbeMethodValidationController;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.ConflictException;
import com.company.seed.common.error.RateLimitExceededException;
import com.company.seed.common.idempotency.IdempotencyFilter;
import com.company.seed.common.limits.PayloadTooLargeException;
import com.company.seed.common.limits.RequestSizeFilter;
import com.company.seed.common.limits.WriteRateLimitFilter;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.logging.RequestLoggingFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One test per row of the story 4 envelope/error matrix, driven through the probe controllers in
 * {@code com.company.fixtures.web}. The handler's logger runs at DEBUG so each test can check the
 * level of the one line the handler writes.
 */
@WebMvcTest(
    // Only the probes and the /error controller: the application's own controllers would need
    // their services, which a web slice does not load.
    controllers = {
      ProbeController.class,
      ProbeMethodValidationController.class,
      JsonErrorController.class
    },
    properties = "logging.level.com.company.seed.common.error.web=DEBUG",
    // The correlation and request-log filters are covered by CorrelationIdIT; without them the
    // correlation ID here is whatever the test puts in the MDC. The idempotency filter needs the
    // database and is covered by IdempotencyIT; the limit filters by RequestSizeIT and
    // WriteRateLimitIT.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
              CorrelationIdFilter.class,
              RequestLoggingFilter.class,
              IdempotencyFilter.class,
              RequestSizeFilter.class,
              WriteRateLimitFilter.class
            }))
@Import({
  ProbeController.class,
  ProbeMethodValidationController.class,
  ApiResponses.class,
  ApiErrorResponseWriter.class
})
// Spring Security's filters are covered by SecurityIT; here only the handler's own mappings count.
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

  private static final String NOW = "2026-11-14T08:00:00Z";
  private static final Set<String> HANDLER_LOGGERS =
      Set.of(GlobalExceptionHandler.class.getName(), JsonErrorController.class.getName());
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mvc;
  @Autowired private MockMvcTester tester;

  @TestConfiguration
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);
    }
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
    TestSecurityContextHolder.clearContext();
  }

  @Test
  void successEnvelopes() {
    MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, "corr-ok");

    assertThat(tester.get().uri("/probe/ok"))
        .hasStatus(HttpStatus.OK)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"success": true, "message": "Thing retrieved", "data": {"id": "1", "name": "first"},
             "timestamp": "%s", "correlationId": "corr-ok"}
            """
                .formatted(NOW));
    assertThat(tester.post().uri("/probe/created"))
        .hasStatus(HttpStatus.CREATED)
        .headers()
        .hasValue("Location", "/probe/2");
    assertThat(tester.post().uri("/probe/accepted"))
        .hasStatus(HttpStatus.ACCEPTED)
        .headers()
        .hasValue("Location", "/api/v1/operations/op-1");
    assertThat(tester.delete().uri("/probe/things/1"))
        .hasStatus(HttpStatus.OK)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"success": true, "message": "Thing deleted", "data": null,
             "timestamp": "%s", "correlationId": "corr-ok"}
            """
                .formatted(NOW));
  }

  @Test
  void pagedEnvelope() {
    assertThat(tester.get().uri("/probe/page"))
        .hasStatus(HttpStatus.OK)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"success": true, "message": "Things retrieved",
             "data": [{"id": "1", "name": "first"}, {"id": "2", "name": "second"}],
             "timestamp": "%s", "correlationId": null,
             "pagination": {"page": 0, "size": 2, "totalElements": 5, "totalPages": 3,
                            "hasNext": true}}
            """
                .formatted(NOW));
  }

  @Test
  void bodyValidationReportsOneErrorPerFieldPath(CapturedOutput output) throws Exception {
    String body =
        """
        {"id": 5, "name": "", "description": "%s", "quantity": 0, "secret": "short",
         "address": {"city": ""}, "items": [{"name": "a"}, {"name": "b"}, {"name": ""}]}
        """
            .formatted("d".repeat(150));

    expectError(json(post("/probe/items"), body), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.message").value("Request validation failed"))
        .andExpect(jsonPath("$.path").value("/probe/items"))
        .andExpect(jsonPath("$.errors", hasSize(7)))
        .andExpect(fieldError(0, "address.city", "NotBlank", "must not be blank"))
        .andExpect(fieldError(1, "description", "Size", "size must be between 0 and 100"))
        .andExpect(jsonPath("$.errors[1].rejectedValue").value("d".repeat(100)))
        .andExpect(fieldError(2, "id", "Null", "must not be set"))
        .andExpect(jsonPath("$.errors[2].rejectedValue").value(5))
        .andExpect(fieldError(3, "items[2].name", "NotBlank", "must not be blank"))
        .andExpect(jsonPath("$.errors[3].rejectedValue").value(""))
        .andExpect(fieldError(4, "name", "NotBlank", "must not be blank"))
        .andExpect(fieldError(5, "quantity", "Min", "must be at least 1"))
        .andExpect(jsonPath("$.errors[5].rejectedValue").value(0))
        .andExpect(fieldError(6, "secret", "Size", "size must be between 12 and 2147483647"))
        .andExpect(jsonPath("$.errors[6].rejectedValue").doesNotExist())
        .andExpect(content().string(not(containsString("short"))));
    assertLoggedOnce(output, "DEBUG");
    assertThat(output.getAll()).as("sensitive value in logs").doesNotContain("short");
  }

  @Test
  void updateGroupAppliesItsOwnRules() throws Exception {
    expectError(json(put("/probe/items/7"), "{\"name\": \"ok\"}"), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(fieldError(0, "id", "NotNull", "is required"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    mvc.perform(json(post("/probe/items"), "{\"name\": \"ok\"}")).andExpect(status().isCreated());
  }

  @Test
  void parameterValidationOnValidatedController(CapturedOutput output) throws Exception {
    expectError(get("/probe/search").param("size", "0"), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(fieldError(0, "size", "Min", "must be at least 1"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value(0));
    assertLoggedOnce(output, "DEBUG");
    expectError(get("/probe/items/0"), 400, "VALIDATION_FAILED")
        .andExpect(fieldError(0, "id", "Min", "must be at least 1"));
  }

  @Test
  void parameterValidationByBuiltInMethodValidation() throws Exception {
    expectError(get("/probe-mv/search").param("size", "0"), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(fieldError(0, "size", "Min", "must be at least 1"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value(0));
  }

  @ParameterizedTest(name = "{0} {1} -> {3} {4}")
  @CsvSource({
    // malformed
    "POST,   /probe/items,                application/json, 400, MALFORMED_REQUEST,      Malformed request,         DEBUG",
    "GET,    /probe/count?count=abc,      ,                 400, MALFORMED_REQUEST,      Malformed request,         DEBUG",
    "GET,    /probe/count,                ,                 400, MALFORMED_REQUEST,      Malformed request,         DEBUG",
    "GET,    /probe/header,               ,                 400, MALFORMED_REQUEST,      Malformed request,         DEBUG",
    // not found
    "GET,    /no/such/route,              ,                 404, NOT_FOUND,              Resource not found,        DEBUG",
    "GET,    /probe/throw/not-found,      ,                 404, THING_NOT_FOUND,        Thing not found,           DEBUG",
    // method / media type
    "DELETE, /probe/ok,                   ,                 405, METHOD_NOT_ALLOWED,     Method not allowed,        DEBUG",
    "POST,   /probe/items,                text/plain,       415, UNSUPPORTED_MEDIA_TYPE, Unsupported media type,    DEBUG",
    // seed exceptions
    "GET,    /probe/throw/conflict,       ,                 409, THING_ALREADY_EXISTS,   Thing already exists,      INFO",
    "GET,    /probe/throw/business,       ,                 422, THING_LIMIT_REACHED,    Thing limit reached,       INFO",
    "GET,    /probe/throw/forbidden,      ,                 403, FORBIDDEN,              Not your thing,            WARN",
    "GET,    /probe/throw/bad-gateway,    ,                 502, EXTERNAL_SERVICE_ERROR, Thing store failed,        WARN",
    "GET,    /probe/throw/unavailable,    ,                 503, SERVICE_UNAVAILABLE,    Thing store failed,        WARN",
    "GET,    /probe/throw/timeout,        ,                 504, EXTERNAL_SERVICE_TIMEOUT, Thing store failed,      WARN",
    // security (method security inside a handler)
    // (nobody is authenticated here, so a denial is the missing authentication)
    "GET,    /probe/throw/access-denied,        ,           401, UNAUTHENTICATED,        Authentication required,   INFO",
    "GET,    /probe/throw/authorization-denied, ,           401, UNAUTHENTICATED,        Authentication required,   INFO",
    "GET,    /probe/throw/unauthenticated,      ,           401, UNAUTHENTICATED,        Authentication required,   INFO",
    // too large
    "GET,    /probe/throw/too-large,      ,                 413, PAYLOAD_TOO_LARGE,      Upload exceeds 10MB,       INFO",
    "GET,    /probe/throw/body-too-large-io,   ,            413, PAYLOAD_TOO_LARGE,      Request body exceeds 1MB,  INFO",
    "GET,    /probe/throw/body-too-large-json, ,            413, PAYLOAD_TOO_LARGE,      Request body exceeds 1MB,  INFO",
    "POST,   /probe/body-too-large,       ,                 413, PAYLOAD_TOO_LARGE,      Request body exceeds 2KB,  INFO",
    // rate limit
    "GET,    /probe/throw/rate-limited,   ,                 429, RATE_LIMITED,           Too many requests,         INFO",
    // persistence
    "GET,    /probe/throw/db-locked,      ,                 409, CONCURRENT_MODIFICATION, The resource was changed by someone else; reload it and try again, INFO",
    "GET,    /probe/throw/db-connection,  ,                 503, SERVICE_UNAVAILABLE,    Service unavailable,       ERROR",
    "GET,    /probe/throw/db-transient,   ,                 503, SERVICE_UNAVAILABLE,    Service unavailable,       ERROR",
    "GET,    /probe/throw/db-recoverable, ,                 503, SERVICE_UNAVAILABLE,    Service unavailable,       ERROR",
    "GET,    /probe/throw/tx-unavailable, ,                 503, SERVICE_UNAVAILABLE,    Service unavailable,       ERROR",
    "GET,    /probe/throw/db-other,       ,                 500, INTERNAL_ERROR,         Unexpected error. Quote the correlation ID., ERROR",
    // Bean Validation outside a controller
    "GET,    /probe/throw/violations-empty,    ,            500, INTERNAL_ERROR,         Unexpected error. Quote the correlation ID., ERROR",
    "GET,    /probe/throw/violations-internal, ,            500, INTERNAL_ERROR,         Unexpected error. Quote the correlation ID., ERROR",
  })
  void mappedErrors(
      String method,
      String uri,
      String contentType,
      int status,
      String errorCode,
      String message,
      String level,
      CapturedOutput output)
      throws Exception {
    MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), uri);
    if (contentType != null) {
      request.contentType(contentType).content("{not json");
    }

    expectError(request, status, errorCode)
        .andExpect(jsonPath("$.message").value(message))
        .andExpect(jsonPath("$.errors", hasSize(0)));
    assertLoggedOnce(output, level);
  }

  @ParameterizedTest
  @CsvSource({
    "/probe/throw/db-connection, CannotGetJdbcConnectionException",
    "/probe/throw/db-other,      InvalidDataAccessResourceUsageException",
    "/probe/throw/tx-unavailable, CannotCreateTransactionException"
  })
  void databaseFailuresAreLoggedWithTheStackTraceButNotAnswered(
      String uri, String exception, CapturedOutput output) throws Exception {
    mvc.perform(get(uri)).andExpect(content().string(not(containsString("secret_table"))));

    List<String> lines = logLines(output, null);
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).startsWith("ERROR").contains(exception).contains("\tat ");
  }

  @Test
  void authenticationFailureChallengesWithTheSchemeOnlyAndEchoesNoReason() throws Exception {
    expectError(get("/probe/throw/unauthenticated"), 401, "UNAUTHENTICATED")
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeaders("WWW-Authenticate"))
                    .containsExactly("Bearer"))
        .andExpect(content().string(not(containsString("secret_table"))));
  }

  @Test
  void accessDeniedEchoesNoReason() throws Exception {
    TestSecurityContextHolder.setAuthentication(
        new TestingAuthenticationToken("u-1", null, "ROLE_USER"));

    expectError(get("/probe/throw/authorization-denied"), 403, "FORBIDDEN")
        .andExpect(content().string(not(containsString("secret_table"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/probe/throw/access-denied", "/probe/throw/authorization-denied"})
  void accessDeniedForAnAuthenticatedCallerIsForbidden(String uri, CapturedOutput output)
      throws Exception {
    TestSecurityContextHolder.setAuthentication(
        new TestingAuthenticationToken("u-1", null, "ROLE_USER"));

    expectError(get(uri), 403, "FORBIDDEN")
        .andExpect(jsonPath("$.message").value("Access denied"))
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    assertLoggedOnce(output, "WARN");
    assertThat(output.getAll()).contains("subject=u-1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/probe/throw/access-denied", "/probe/throw/authorization-denied"})
  void accessDeniedForNobodyIsUnauthenticatedWithTheBearerChallenge(String uri) throws Exception {
    TestSecurityContextHolder.setAuthentication(
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    expectError(get(uri), 401, "UNAUTHENTICATED")
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
  }

  @Test
  void internalValidationEchoesNoFieldOrValue() throws Exception {
    expectError(get("/probe/throw/violations-internal"), 500, "INTERNAL_ERROR")
        .andExpect(jsonPath("$.errors", hasSize(0)))
        .andExpect(content().string(not(containsString("name"))))
        .andExpect(content().string(not(containsString("short-secret"))))
        .andExpect(content().string(not(containsString("must not be blank"))));
  }

  @Test
  void methodNotAllowedAnnouncesAllowedMethods() {
    MvcTestResult result = tester.delete().uri("/probe/ok").exchange();

    assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(result.getResponse().getHeader("Allow")).contains("GET");
  }

  @Test
  void externalServiceFailureIsLoggedWithDependency(CapturedOutput output) throws Exception {
    mvc.perform(get("/probe/throw/timeout")).andExpect(status().isGatewayTimeout());

    assertThat(output.getAll())
        .contains("dependency=thing-store")
        .contains("cause=java.net.SocketTimeoutException: read timed out");
  }

  @Test
  void unexpectedExceptionIsHiddenAndLoggedOnceAtErrorWithStack(CapturedOutput output)
      throws Exception {
    MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, "corr-500");

    MvcResult result =
        expectError(get("/probe/throw/boom"), 500, "INTERNAL_ERROR")
            .andExpect(jsonPath("$.message").value("Unexpected error. Quote the correlation ID."))
            .andExpect(jsonPath("$.correlationId").value("corr-500"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain("secret_table")
        .doesNotContain("IllegalStateException")
        .doesNotContain("com.company")
        .doesNotContain("\tat ");
    List<String> errorLines = logLines(output, "ERROR");
    assertThat(errorLines).hasSize(1);
    assertThat(errorLines.get(0)).contains("corr-500").contains("INTERNAL_ERROR");
    assertThat(output.getAll())
        .contains("java.lang.IllegalStateException: " + ProbeController.SECRET_SQL)
        .contains("at com.company.fixtures.web.ProbeController.fail");
  }

  @ParameterizedTest(name = "/error with {0} -> {1}")
  @CsvSource({
    "400, MALFORMED_REQUEST,        DEBUG",
    "401, UNAUTHENTICATED,          INFO",
    "403, FORBIDDEN,                WARN",
    "404, NOT_FOUND,                DEBUG",
    "405, METHOD_NOT_ALLOWED,       DEBUG",
    "413, PAYLOAD_TOO_LARGE,        INFO",
    "415, UNSUPPORTED_MEDIA_TYPE,   DEBUG",
    "418, MALFORMED_REQUEST,        DEBUG",
    "429, RATE_LIMITED,             INFO",
    "500, INTERNAL_ERROR,           ERROR",
    "502, EXTERNAL_SERVICE_ERROR,   ERROR",
    "503, SERVICE_UNAVAILABLE,      ERROR",
    "504, EXTERNAL_SERVICE_TIMEOUT, ERROR"
  })
  void errorDispatchMapsEachStatus(
      int status, String errorCode, String level, CapturedOutput output) throws Exception {
    expectError(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/broken"),
            status,
            errorCode)
        .andExpect(jsonPath("$.path").value("/api/v1/broken"));
    assertLoggedOnce(output, level);
  }

  @Test
  void errorDispatchOfAnUnexpectedExceptionHidesIt(CapturedOutput output) throws Exception {
    expectError(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/broken")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException(ProbeController.SECRET_SQL)),
            500,
            "INTERNAL_ERROR")
        .andExpect(content().string(not(containsString("secret_table"))));
    assertThat(logLines(output, "ERROR")).hasSize(1);
  }

  @Test
  void errorDispatchOfASeedExceptionKeepsItsMapping(CapturedOutput output) throws Exception {
    expectError(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/things")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new ServletException(
                        new ConflictException("THING_ALREADY_EXISTS", "Thing already exists"))),
            409,
            "THING_ALREADY_EXISTS")
        .andExpect(jsonPath("$.message").value("Thing already exists"));
    assertLoggedOnce(output, "INFO");
  }

  @ParameterizedTest(name = "{0} -> Retry-After: {1}")
  @CsvSource({"/probe/throw/rate-limited, 7", "/probe/throw/rate-limited-fraction, 7"})
  void rateLimitExceptionSendsRetryAfterInWholeSecondsRoundedUp(String uri, String retryAfter)
      throws Exception {
    expectError(get(uri), 429, "RATE_LIMITED")
        .andExpect(header().string("Retry-After", retryAfter))
        .andExpect(jsonPath("$.message").value("Too many requests"));
  }

  @ParameterizedTest(name = "file {0}, request {1} -> {2}")
  @CsvSource({
    "10MB, 10MB, Upload exceeds 10MB",
    "5MB,  10MB, Upload exceeds 5MB",
    "-1,   2KB,  Upload exceeds 2KB",
    "-1,   -1,   Payload too large"
  })
  void theUpload413NamesTheSmallestConfiguredLimit(String file, String request, String message) {
    assertThat(GlobalExceptionHandler.uploadTooLargeMessage(file, request)).isEqualTo(message);
  }

  @Test
  void errorDispatchOfAPayloadTooLargeInTheCauseChainIs413(CapturedOutput output) throws Exception {
    expectError(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/things")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new ServletException(new PayloadTooLargeException("1MB"))),
            413,
            "PAYLOAD_TOO_LARGE")
        .andExpect(jsonPath("$.message").value("Request body exceeds 1MB"))
        .andExpect(jsonPath("$.path").value("/api/v1/things"));
    assertLoggedOnce(output, "INFO");
  }

  @Test
  void errorDispatchOfARateLimitExceptionKeepsItsRetryAfter(CapturedOutput output)
      throws Exception {
    expectError(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/things")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new ServletException(new RateLimitExceededException(Duration.ofSeconds(7)))),
            429,
            "RATE_LIMITED")
        .andExpect(header().string("Retry-After", "7"));
    assertLoggedOnce(output, "INFO");
  }

  @Test
  void notAcceptableIsAClientErrorInJson(CapturedOutput output) throws Exception {
    expectError(get("/probe/ok").accept(MediaType.APPLICATION_XML), 406, "MALFORMED_REQUEST");
    assertLoggedOnce(output, "DEBUG");
  }

  @Test
  void bindingTypeMismatchHidesJavaTypes() throws Exception {
    expectError(get("/probe/filter").param("count", "abc"), 400, "VALIDATION_FAILED")
        .andExpect(fieldError(0, "count", "typeMismatch", "Invalid value"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
        .andExpect(content().string(not(containsString("java.lang"))));
  }

  @Test
  void methodValidationReportsBodyFieldPaths() throws Exception {
    String body =
        """
        {"name": "", "items": [{"name": "a"}, {"name": "b"}, {"name": ""}]}
        """;

    expectError(json(put("/probe-mv/items/0"), body), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(3)))
        .andExpect(fieldError(0, "id", "Min", "must be at least 1"))
        .andExpect(fieldError(1, "items[2].name", "NotBlank", "must not be blank"))
        .andExpect(fieldError(2, "name", "NotBlank", "must not be blank"));
  }

  @Test
  void methodValidationReportsListElementIndices() throws Exception {
    expectError(
            json(post("/probe-mv/items"), "[{\"name\": \"a\"}, {\"name\": \"\"}]"),
            400,
            "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(fieldError(0, "[1].name", "NotBlank", "must not be blank"));
  }

  @Test
  void nestedSensitiveAndNonScalarValuesAreNotEchoed() throws Exception {
    String body =
        """
        {"name": "ok", "address": {"city": "Pune", "token": "short"},
         "items": [{"name": "a"}, {"name": "b"}, {"name": "c"}, {"name": "d"}]}
        """;

    expectError(json(post("/probe/items"), body), 400, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.errors", hasSize(2)))
        .andExpect(fieldError(0, "address.token", "Size", "size must be between 12 and 2147483647"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
        .andExpect(jsonPath("$.errors[1].field").value("items"))
        .andExpect(jsonPath("$.errors[1].rejectedValue").doesNotExist())
        .andExpect(content().string(not(containsString("short"))));
  }

  @Test
  void directRequestToErrorIsNotFound() throws Exception {
    expectError(get("/error"), 404, "NOT_FOUND").andExpect(jsonPath("$.path").value("/error"));
  }

  private ResultActions expectError(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    return mvc.perform(request)
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.errorCode").value(code))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.timestamp").value(NOW))
        .andExpect(jsonPath("$.correlationId").hasJsonPath());
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static ResultMatcher fieldError(int index, String field, String code, String message) {
    String prefix = "$.errors[" + index + "].";
    return result -> {
      jsonPath(prefix + "field").value(field).match(result);
      jsonPath(prefix + "code").value(code).match(result);
      jsonPath(prefix + "message").value(message).match(result);
    };
  }

  /** The exception was logged exactly once, by the error package, at {@code level}. */
  private static void assertLoggedOnce(CapturedOutput output, String level) {
    List<String> lines = logLines(output, null);
    assertThat(lines).as("log lines of the error handler").hasSize(1);
    assertThat(lines.get(0)).as("level").startsWith(level);
  }

  /**
   * Lines of {@code GlobalExceptionHandler}/{@code JsonErrorController}, or every line at a level,
   * each as {@code "<level> <message> <stack trace>"}. The console output is ECS JSON, one object
   * per line.
   */
  private static List<String> logLines(CapturedOutput output, String level) {
    List<String> lines = new ArrayList<>();
    for (String raw : output.getAll().split("\\R")) {
      if (!raw.startsWith("{")) {
        continue;
      }
      JsonNode line = JSON.readTree(raw);
      String lineLevel = line.path("log").path("level").asString();
      String logger = line.path("log").path("logger").asString();
      if (level != null ? lineLevel.equals(level) : HANDLER_LOGGERS.contains(logger)) {
        lines.add(
            lineLevel
                + " "
                + line.path("message").asString()
                + " "
                + line.path("error").path("stack_trace").asString(""));
      }
    }
    return lines;
  }
}
