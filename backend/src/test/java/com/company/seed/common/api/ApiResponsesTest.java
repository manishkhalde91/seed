package com.company.seed.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ApiResponsesTest {

  private static final Instant NOW = Instant.parse("2026-11-14T08:00:00Z");

  private final ApiResponses responses = new ApiResponses(Clock.fixed(NOW, ZoneOffset.UTC));
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @BeforeEach
  void setCorrelationId() {
    MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, "corr-1");
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void okWrapsDataWithClockTimestampAndMdcCorrelationId() {
    ResponseEntity<ApiResponse<String>> entity = responses.ok("Thing retrieved", "thing");

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(entity.getBody())
        .isEqualTo(new ApiResponse<>(true, "Thing retrieved", "thing", NOW, "corr-1", null));
  }

  @Test
  void createdIs201WithLocation() {
    ResponseEntity<ApiResponse<String>> entity =
        responses.created("Thing created", "thing", URI.create("/api/v1/things/1"));

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(entity.getHeaders().getLocation()).isEqualTo(URI.create("/api/v1/things/1"));
    assertThat(entity.getBody().data()).isEqualTo("thing");
  }

  @Test
  void acceptedIs202WithOperationLocation() {
    ResponseEntity<ApiResponse<String>> entity =
        responses.accepted("Export started", "op-1", URI.create("/api/v1/operations/op-1"));

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(entity.getHeaders().getLocation()).isEqualTo(URI.create("/api/v1/operations/op-1"));
    assertThat(entity.getBody().data()).isEqualTo("op-1");
  }

  @Test
  void deletedWritesNullDataAndNoPagination() {
    ResponseEntity<ApiResponse<Void>> entity = responses.deleted("Thing deleted");

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> json = toMap(entity.getBody());
    assertThat(json)
        .containsOnlyKeys("success", "message", "data", "timestamp", "correlationId")
        .containsEntry("data", null)
        .containsEntry("timestamp", "2026-11-14T08:00:00Z")
        .containsEntry("correlationId", "corr-1");
  }

  @Test
  void pageWritesItemsAsDataAndPagination() {
    ResponseEntity<ApiResponse<List<String>>> entity =
        responses.page("Things retrieved", new PageResult<>(List.of("a", "b"), 1, 2, 5));

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(entity.getBody().data()).containsExactly("a", "b");
    assertThat(entity.getBody().pagination()).isEqualTo(new PageMeta(1, 2, 5, 3, true));
    assertThat(toMap(entity.getBody()))
        .containsOnlyKeys("success", "message", "data", "timestamp", "correlationId", "pagination");
  }

  @Test
  void lastPageHasNoNext() {
    assertThat(new PageResult<>(List.of("e"), 2, 2, 5).hasNext()).isFalse();
    assertThat(new PageResult<>(List.of(), 0, 20, 0).totalPages()).isZero();
  }

  @Test
  void pageResultRejectsInvalidPaging() {
    assertThatThrownBy(() -> new PageResult<>(List.of(), 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageResult<>(List.of(), -1, 20, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void correlationIdIsNullWithoutMdcAndStillWritten() {
    MDC.clear();

    Map<String, Object> json = toMap(responses.ok("Thing retrieved", "thing").getBody());

    assertThat(json).containsEntry("correlationId", null);
  }

  private Map<String, Object> toMap(Object body) {
    return jsonMapper.readValue(
        jsonMapper.writeValueAsString(body), new TypeReference<Map<String, Object>>() {});
  }
}
