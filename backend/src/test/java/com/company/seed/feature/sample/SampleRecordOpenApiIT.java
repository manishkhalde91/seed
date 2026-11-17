package com.company.seed.feature.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DEMO ONLY. The sample endpoints in the OpenAPI contract (api-standards.md §7): every 2xx is the
 * {@code ApiResponse} envelope around the right type, the list adds {@code pagination}, and every
 * operation documents the error envelope and the correlation header. Lives with the sample, so
 * deleting the module deletes it too.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "SEED_API_DOCS_ENABLED=true")
@Import({TestDatabase.class, TestSecurity.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampleRecordOpenApiIT {

  private static final String BASE = "/api/v1/sample-records";
  private static final String SCHEMAS = "#/components/schemas/";
  private static final List<String> ERROR_STATUSES =
      List.of("400", "401", "403", "404", "409", "422", "500");

  @LocalServerPort private int port;

  private JsonNode contract;

  @BeforeAll
  void fetchContract() {
    String json =
        LenientRestClient.anonymous(port).get().uri("/v3/api-docs").retrieve().body(String.class);
    contract = JsonMapper.builder().build().readTree(json);
  }

  @Test
  void createIs201WithTheEnvelopeAroundSampleRecordResponse() {
    JsonNode created = operation(BASE, "post").get("responses").get("201");

    JsonNode schema = created.get("content").get("application/json").get("schema");
    assertThat(schema.get("allOf").get(0).get("$ref").asString())
        .isEqualTo(SCHEMAS + "ApiResponse");
    assertThat(schema.get("allOf").get(1).get("properties").get("data").get("$ref").asString())
        .isEqualTo(SCHEMAS + "SampleRecordResponse");
    assertThat(created.get("headers").has("Location")).isTrue();
    assertThat(operation(BASE, "post").get("responses").has("200")).isFalse();
  }

  @Test
  void theListIsAPagedArrayWithTheSortWhitelist() {
    JsonNode list = operation(BASE, "get");
    JsonNode typed =
        list.get("responses")
            .get("200")
            .get("content")
            .get("application/json")
            .get("schema")
            .get("allOf")
            .get(1);

    assertThat(typed.get("properties").get("data").get("items").get("$ref").asString())
        .isEqualTo(SCHEMAS + "SampleRecordResponse");
    assertThat(typed.get("properties").get("pagination").get("$ref").asString())
        .isEqualTo(SCHEMAS + "PageMeta");
    assertThat(names(typed.get("required"))).contains("data", "pagination");
    List<String> parameters = new ArrayList<>();
    list.get("parameters").forEach(parameter -> parameters.add(parameter.get("name").asString()));
    assertThat(parameters).contains("page", "size", "sort").doesNotContain("query");
    assertThat(parameter(list, "sort").get("description").asString())
        .contains("createdAt, name")
        .contains("createdAt,desc");
  }

  @Test
  void deleteReturnsTheEnvelopeWithNullData() {
    JsonNode data =
        operation(BASE + "/{id}", "delete")
            .get("responses")
            .get("200")
            .get("content")
            .get("application/json")
            .get("schema")
            .get("allOf")
            .get(1)
            .get("properties")
            .get("data");

    assertThat(data.get("type").asString()).isEqualTo("null");
  }

  @Test
  void everySampleOperationDocumentsTheErrorsAndTheCorrelationHeader() {
    Map<String, List<String>> operations =
        Map.of(
            BASE,
            List.of("get", "post"),
            BASE + "/{id}",
            List.of("get", "put", "delete"),
            BASE + "/{id}/restore",
            List.of("post"));
    operations.forEach(
        (path, methods) ->
            methods.forEach(
                method -> {
                  JsonNode operation = operation(path, method);
                  JsonNode responses = operation.get("responses");
                  for (String status : ERROR_STATUSES) {
                    assertThat(
                            responses
                                .get(status)
                                .get("content")
                                .get("application/json")
                                .get("schema")
                                .get("$ref")
                                .asString())
                        .as("%s %s %s", method, path, status)
                        .isEqualTo(SCHEMAS + "ApiErrorResponse");
                  }
                  responses
                      .properties()
                      .forEach(
                          response ->
                              assertThat(response.getValue().get("headers").has("X-Correlation-Id"))
                                  .as("%s %s %s header", method, path, response.getKey())
                                  .isTrue());
                  JsonNode header = parameter(operation, "X-Correlation-Id");
                  assertThat(header.get("in").asString()).isEqualTo("header");
                  assertThat(header.get("required").asBoolean()).isFalse();
                }));
  }

  @Test
  void schemasStateNotBlankRequiredAndNullableAndOperationIdsAreUnique() {
    JsonNode schemas = contract.get("components").get("schemas");
    for (String request : List.of("CreateSampleRecordRequest", "UpdateSampleRecordRequest")) {
      assertThat(schemas.get(request).get("properties").get("name").get("minLength").asInt())
          .as(request)
          .isEqualTo(1);
    }
    JsonNode response = schemas.get("SampleRecordResponse");
    assertThat(names(response.get("required")))
        .containsExactlyInAnyOrder(
            "id",
            "name",
            "description",
            "version",
            "createdAt",
            "createdBy",
            "updatedAt",
            "updatedBy");
    assertThat(names(response.get("properties").get("description").get("type")))
        .containsExactlyInAnyOrder("string", "null");
    assertThat(
            List.of(
                operation(BASE, "get"),
                operation(BASE, "post"),
                operation(BASE + "/{id}", "get"),
                operation(BASE + "/{id}", "put"),
                operation(BASE + "/{id}", "delete"),
                operation(BASE + "/{id}/restore", "post")))
        .extracting(operation -> operation.get("operationId").asString())
        .containsExactly(
            "listSampleRecords",
            "createSampleRecord",
            "getSampleRecord",
            "updateSampleRecord",
            "deleteSampleRecord",
            "restoreSampleRecord");
  }

  private JsonNode operation(String path, String method) {
    JsonNode operation = contract.get("paths").get(path).get(method);
    assertThat(operation).as("%s %s", method, path).isNotNull();
    return operation;
  }

  private static JsonNode parameter(JsonNode operation, String name) {
    for (JsonNode parameter : operation.get("parameters")) {
      if (parameter.get("name").asString().equals(name)) {
        return parameter;
      }
    }
    throw new AssertionError("No parameter " + name);
  }

  private static List<String> names(JsonNode array) {
    List<String> names = new ArrayList<>();
    array.forEach(name -> names.add(name.asString()));
    return names;
  }
}
