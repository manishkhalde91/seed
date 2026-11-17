package com.company.seed.common.error.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.logging.EcsLogLines.Line;
import com.company.fixtures.persistence.AuditedThing;
import com.company.fixtures.persistence.PersistenceFixtures;
import com.company.fixtures.persistence.PersistenceTest;
import com.company.fixtures.persistence.PersistenceTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persistence rows of errors-and-logging.md §2 against a real database: optimistic locking,
 * mapped and unmapped constraint violations, and Bean Validation failing inside JPA. The body never
 * carries SQL or constraint text; the handler's one log line names the constraint.
 */
@PersistenceTest
@ExtendWith(OutputCaptureExtension.class)
class PersistenceErrorMappingIT extends PersistenceTestSupport {

  private static final String HANDLER = GlobalExceptionHandler.class.getName();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void aStaleVersionIsAConcurrentModification(CapturedOutput output) {
    AuditedThing thing = create(unique("locked"));

    ResponseEntity<String> response =
        client()
            .post()
            .uri("/probe-db/things/{id}/stale-save", thing.getId())
            .retrieve()
            .toEntity(String.class);

    Map<String, Object> body = parse(response);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(body).containsEntry("errorCode", "CONCURRENT_MODIFICATION");
    assertThat(handlerLines(output))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.level()).isEqualTo("INFO");
              assertThat(line.stackTrace()).isEmpty();
            });
  }

  @Test
  void aDuplicateOnAMappedConstraintIsAConflictWithTheMappedCode(CapturedOutput output) {
    String name = unique("duplicate");
    assertThat(postThing(name, 1).getStatusCode().value()).isEqualTo(200);

    ResponseEntity<String> response = postThing(name, 1);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(parse(response))
        .containsEntry("errorCode", PersistenceFixtures.ALREADY_EXISTS)
        .containsEntry("message", "Conflicts with existing data")
        .containsEntry("errors", List.of());
    assertNoDatabaseText(response.getBody());
    assertThat(handlerLines(output))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.level()).isEqualTo("WARN");
              assertThat(line.message()).contains("constraint=" + PersistenceFixtures.UNIQUE_NAME);
              assertThat(line.stackTrace()).isEmpty();
            });
    assertNoValuesLogged(output, name);
  }

  @Test
  void anUnmappedConstraintIsAnInternalDataIntegrityViolation(CapturedOutput output) {
    String name = unique("negative");
    ResponseEntity<String> response = postThing(name, -1);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(parse(response))
        .containsEntry("errorCode", "DATA_INTEGRITY_VIOLATION")
        .containsEntry("message", "Unexpected error. Quote the correlation ID.")
        .containsEntry("errors", List.of());
    assertNoDatabaseText(response.getBody());
    assertThat(handlerLines(output))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.level()).isEqualTo("ERROR");
              assertThat(line.message())
                  .contains("constraint=" + PersistenceFixtures.QUANTITY_CHECK);
              assertThat(line.stackTrace()).contains("DataIntegrityViolationException");
            });
    assertNoValuesLogged(output, name);
  }

  @Test
  void beanValidationFailingInsideJpaIsAnInternalError(CapturedOutput output) {
    ResponseEntity<String> response = postThing(" ", 1);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(parse(response))
        .containsEntry("errorCode", "INTERNAL_ERROR")
        .containsEntry("errors", List.of());
    assertThat(response.getBody()).doesNotContain("name").doesNotContain("must not be blank");
    assertThat(handlerLines(output))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.level()).isEqualTo("ERROR");
              assertThat(line.stackTrace()).contains("ConstraintViolationException");
            });
  }

  private ResponseEntity<String> postThing(String name, int quantity) {
    return client()
        .post()
        .uri(
            builder ->
                builder
                    .path("/probe-db/things")
                    .queryParam("name", name)
                    .queryParam("quantity", quantity)
                    .build())
        .retrieve()
        .toEntity(String.class);
  }

  private static void assertNoDatabaseText(String body) {
    assertThat(body)
        .doesNotContainIgnoringCase("audited_thing")
        .doesNotContainIgnoringCase("constraint")
        .doesNotContainIgnoringCase("insert")
        .doesNotContainIgnoringCase("duplicate")
        .doesNotContainIgnoringCase("sql");
  }

  /**
   * Nothing but the handler logs the exception (Hibernate's own SQL error logging is off), and no
   * line anywhere carries the row's values: the server's "Detail:" text is not requested.
   */
  private static void assertNoValuesLogged(CapturedOutput output, String submittedName) {
    assertThat(EcsLogLines.all(output.getAll()))
        .filteredOn(line -> !line.level().equals("INFO") && !line.level().equals("DEBUG"))
        .extracting(Line::logger)
        .containsOnly(HANDLER);
    assertThat(output.getAll())
        .doesNotContain(submittedName)
        .doesNotContain("Failing row contains")
        .doesNotContain("Key (");
  }

  private static List<Line> handlerLines(CapturedOutput output) {
    return EcsLogLines.of(output.getAll(), HANDLER);
  }

  private static Map<String, Object> parse(ResponseEntity<String> response) {
    return JSON.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
  }
}
