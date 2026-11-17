package com.company.seed.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.web.LenientRestClient;
import com.company.fixtures.web.ProbeController;
import com.company.fixtures.web.StatusProbe;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The {@code local} profile writes readable pattern lines, not JSON, each carrying {@code
 * [<correlationId>]}, and turns the API docs on when {@code SEED_API_DOCS_ENABLED} is unset. Runs
 * in its own JVM (the localProfileTest task), because Spring Boot configures logging only for the
 * first application context of a JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import({TestDatabase.class, TestSecurity.class, ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class LocalReadableLogIT {

  @LocalServerPort private int port;

  @Test
  void requestLineIsAReadablePatternLineWithTheCorrelationId(CapturedOutput output) {
    LenientRestClient.forPort(port)
        .get()
        .uri("/probe/ok")
        .header(CorrelationIdFilter.HEADER, "abc")
        .retrieve()
        .toBodilessEntity();

    List<String> lines =
        await()
            .atMost(Duration.ofSeconds(5))
            .until(
                () ->
                    Arrays.stream(output.getOut().split("\\R"))
                        .filter(line -> line.contains(RequestLoggingFilter.class.getSimpleName()))
                        .filter(line -> line.contains("GET /probe/ok -> 200"))
                        .toList(),
                found -> !found.isEmpty());
    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst()).contains("[abc]").doesNotStartWith("{");
  }

  @Test
  void apiDocsAreOnByDefault() {
    assertThat(StatusProbe.status(port, "/v3/api-docs")).isEqualTo(200);
  }
}
