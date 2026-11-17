package com.company.seed.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.PagingProbeController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link PageQuery} binding over HTTP, on a probe endpoint ({@code @Sortable(fields = {"name",
 * "rank"}, defaultSort = "rank,desc")}): defaults and valid values are bound, and every invalid
 * {@code page}, {@code size} or {@code sort} is 400 {@code VALIDATION_FAILED} naming it. Shares its
 * context with {@code EnvelopeDocumentationIT}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "SEED_API_DOCS_ENABLED=true")
@Import({TestDatabase.class, TestSecurity.class, PagingProbeController.class})
class PageQueryBindingIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @ParameterizedTest(name = "[{index}] ?{0}")
  @CsvSource({
    "'', 0, 20, rank:DESC",
    "'page=2&size=5&sort=name', 2, 5, name:ASC",
    "'sort=name,desc&sort=rank,ASC', 0, 20, 'name:DESC,rank:ASC'"
  })
  void bindsDefaultsAndValidValues(String query, int page, int size, String sort) {
    JsonNode body = get(query, 200);

    JsonNode item = body.get("data").get(0);
    assertThat(item.get("page").asInt()).isEqualTo(page);
    assertThat(item.get("size").asInt()).isEqualTo(size);
    assertThat(item.get("sort").asString()).isEqualTo(sort);
    assertThat(body.get("pagination").get("page").asInt()).isEqualTo(page);
  }

  @ParameterizedTest(name = "[{index}] ?{0}")
  @CsvSource({
    "'page=-1', page, Min",
    "'page=x', page, typeMismatch",
    "'page=30000000&size=100', page, Max",
    "'size=0', size, Min",
    "'size=101', size, Max",
    "'size=abc', size, typeMismatch",
    "'sort=secret', sort, SortField",
    "'sort=', sort, SortField",
    "'sort=name,up', sort, SortDirection",
    "'sort=name&sort=name,desc', sort, SortDuplicate",
    "'sort=name&sort=rank&sort=name', sort, SortCount"
  })
  void anInvalidParameterIs400NamingIt(String query, String field, String code) {
    JsonNode body = get(query, 400);

    assertThat(body.get("errorCode").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(body.get("errors")).hasSize(1);
    assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo(field);
    assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo(code);
  }

  private JsonNode get(String query, int expectedStatus) {
    ResponseEntity<String> response =
        LenientRestClient.forPort(port)
            .get()
            .uri(PagingProbeController.PATH + (query.isEmpty() ? "" : "?" + query))
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(expectedStatus);
    return JSON.readTree(response.getBody());
  }
}
