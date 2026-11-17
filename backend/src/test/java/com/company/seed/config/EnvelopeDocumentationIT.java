package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.PagingProbeController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EnvelopeOperationCustomizer} on a probe operation outside any feature module: the typed
 * {@code allOf} envelope with a required {@code pagination}, the error envelopes, the correlation
 * header and the paging parameters. Shares its context with {@code PageQueryBindingIT}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "SEED_API_DOCS_ENABLED=true")
@Import({TestDatabase.class, TestSecurity.class, PagingProbeController.class})
class EnvelopeDocumentationIT {

  private static final String SCHEMAS = "#/components/schemas/";

  @LocalServerPort private int port;

  /** The probe's operation in the served document. */
  private JsonNode probeOperation() {
    String json =
        LenientRestClient.anonymous(port).get().uri("/v3/api-docs").retrieve().body(String.class);
    return JsonMapper.builder()
        .build()
        .readTree(json)
        .get("paths")
        .get(PagingProbeController.PATH)
        .get("get");
  }

  @Test
  void theSuccessIsTheTypedEnvelopeWithRequiredPagination() {
    JsonNode schema =
        probeOperation()
            .get("responses")
            .get("200")
            .get("content")
            .get("application/json")
            .get("schema");

    assertThat(schema.get("allOf").get(0).get("$ref").asString())
        .isEqualTo(SCHEMAS + "ApiResponse");
    JsonNode typed = schema.get("allOf").get(1);
    assertThat(typed.get("properties").get("data").get("items").get("$ref").asString())
        .isEqualTo(SCHEMAS + "PagingProbeItem");
    assertThat(typed.get("properties").get("pagination").get("$ref").asString())
        .isEqualTo(SCHEMAS + "PageMeta");
    assertThat(texts(typed.get("required"))).containsExactlyInAnyOrder("data", "pagination");
  }

  @Test
  void everyErrorStatusIsTheErrorEnvelopeAndEveryResponseCarriesTheCorrelationHeader() {
    JsonNode responses = probeOperation().get("responses");

    for (String status : List.of("400", "401", "403", "404", "409", "422", "500")) {
      assertThat(
              responses
                  .get(status)
                  .get("content")
                  .get("application/json")
                  .get("schema")
                  .get("$ref")
                  .asString())
          .as(status)
          .isEqualTo(SCHEMAS + "ApiErrorResponse");
    }
    responses
        .properties()
        .forEach(
            response ->
                assertThat(response.getValue().get("headers").has("X-Correlation-Id"))
                    .as(response.getKey())
                    .isTrue());
  }

  @Test
  void theParametersAreTheCorrelationHeaderAndPageSizeSort() {
    List<String> names = new ArrayList<>();
    probeOperation()
        .get("parameters")
        .forEach(
            parameter ->
                names.add(parameter.get("in").asString() + ":" + parameter.get("name").asString()));

    assertThat(names)
        .containsExactlyInAnyOrder(
            "header:X-Correlation-Id", "query:page", "query:size", "query:sort");
  }

  private static List<String> texts(JsonNode array) {
    List<String> texts = new ArrayList<>();
    array.forEach(text -> texts.add(text.asString()));
    return texts;
  }
}
