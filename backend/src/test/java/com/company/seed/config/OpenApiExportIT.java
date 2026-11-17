package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

/**
 * Exports the OpenAPI contract with the docs flag on and checks its content. The bytes are written
 * to the {@code seed.openapi.output} file, which the {@code checkOpenApi} Gradle task compares with
 * the committed {@code backend/openapi/api.yaml}. Only production endpoints are loaded: no probes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "SEED_API_DOCS_ENABLED=true")
@Import({TestDatabase.class, TestSecurity.class})
class OpenApiExportIT {

  private static final String OUTPUT_PROPERTY = "seed.openapi.output";

  @LocalServerPort private int port;

  private RestClient client;

  @BeforeEach
  void createClient() {
    client =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build();
  }

  @Test
  void exportsADeterministicContractWithAFixedServerAndTheEnvelopeSchemas() throws IOException {
    byte[] first = fetch("/v3/api-docs.yaml");
    byte[] second = fetch("/v3/api-docs.yaml");

    assertThat(second).isEqualTo(first);
    Map<String, Object> contract = new Yaml().load(new String(first, StandardCharsets.UTF_8));
    assertThat(contract.get("servers")).isEqualTo(List.of(Map.of("url", "/")));
    assertThat(contract.get("info")).isEqualTo(Map.of("title", "Seed API", "version", "v1"));
    assertThat(contract.get("components")).isInstanceOf(Map.class);
    Map<?, ?> components = (Map<?, ?>) contract.get("components");
    assertThat(components.get("schemas")).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) components.get("schemas")).keySet())
        .map(Object::toString)
        .contains("ApiResponse", "ApiErrorResponse", "FieldError", "PageMeta");
    Map<?, ?> schemas = (Map<?, ?>) components.get("schemas");
    assertThat((List<Object>) schema(schemas, "ApiErrorResponse").get("required"))
        .contains("errors", "correlationId");
    assertThat((List<Object>) schema(schemas, "ApiResponse").get("required")).contains("data");
    Map<?, ?> field =
        (Map<?, ?>) ((Map<?, ?>) schema(schemas, "FieldError").get("properties")).get("field");
    assertThat((List<Object>) field.get("type")).contains("null");
    Map<?, ?> me = (Map<?, ?>) ((Map<?, ?>) contract.get("paths")).get("/api/v1/me");
    assertThat(((Map<?, ?>) me.get("get")).get("operationId")).isEqualTo("getCurrentUser");
    assertThat((List<Object>) schema(schemas, "MeResponse").get("required"))
        .containsExactlyInAnyOrder("subject", "name", "roles");

    String output = System.getProperty(OUTPUT_PROPERTY);
    if (output != null) {
      Path target = Path.of(output);
      Files.createDirectories(target.toAbsolutePath().getParent());
      Files.write(target, first);
    }
  }

  @Test
  void servesTheJsonDocumentAndSwaggerUiWhenTheFlagIsOn() {
    assertThat(status("/v3/api-docs")).isEqualTo(200);
    assertThat(status("/swagger-ui/index.html")).isEqualTo(200);
  }

  private static Map<?, ?> schema(Map<?, ?> schemas, String name) {
    return (Map<?, ?>) schemas.get(name);
  }

  private byte[] fetch(String path) {
    ResponseEntity<byte[]> response = client.get().uri(path).retrieve().toEntity(byte[].class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotEmpty();
    return response.getBody();
  }

  private int status(String path) {
    return client.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
  }
}
