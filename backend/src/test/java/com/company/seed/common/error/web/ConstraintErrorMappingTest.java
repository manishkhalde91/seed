package com.company.seed.common.error.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.seed.common.error.ConstraintErrorMapping;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ConstraintErrorMappingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withBean(ApiErrorResponseWriter.class)
          .withBean(GlobalExceptionHandler.class);

  @Test
  void twoBeansMappingTheSameConstraintFailStartup() {
    runner
        .withBean(
            "first",
            ConstraintErrorMapping.class,
            () -> ConstraintErrorMapping.of("uq_x__name", "A"))
        .withBean(
            "second",
            ConstraintErrorMapping.class,
            () -> ConstraintErrorMapping.of("UQ_X__NAME", "B"))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mapped twice"));
  }

  @Test
  void distinctConstraintsAcrossBeansStartUp() {
    runner
        .withBean(
            "first", ConstraintErrorMapping.class, () -> ConstraintErrorMapping.of("uq_a", "A"))
        .withBean(
            "second",
            ConstraintErrorMapping.class,
            () -> new ConstraintErrorMapping(Map.of("uq_b", "B", "uq_c", "C")))
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void blankNamesOrCodesAreRejected() {
    assertThatThrownBy(() -> ConstraintErrorMapping.of(" ", "A"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConstraintErrorMapping.of("uq_a", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"uq_thing__name", "fixture.uq_thing__name", "UQ_Thing__Name"})
  void qualifiedOrDifferentlyCasedNamesResolveToTheMapping(String reported) {
    GlobalExceptionHandler handler = handler(ConstraintErrorMapping.of("uq_thing__name", "MAPPED"));

    String constraint = GlobalExceptionHandler.constraintName(integrityViolation(reported));

    assertThat(handler.mappedErrorCode(constraint)).isEqualTo("MAPPED");
    assertThat(handler.handleDataIntegrity(integrityViolation(reported), request()))
        .extracting(ResponseEntity::getStatusCode)
        .extracting(status -> status.value())
        .isEqualTo(409);
  }

  @Test
  void aViolationWithoutAConstraintNameIsAnUnknownInternalViolation(CapturedOutput output) {
    GlobalExceptionHandler handler = handler(ConstraintErrorMapping.of("uq_thing__name", "MAPPED"));
    DataIntegrityViolationException ex = integrityViolation(null);

    ResponseEntity<?> response = handler.handleDataIntegrity(ex, request());

    assertThat(GlobalExceptionHandler.constraintName(ex)).isNull();
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(output.getAll()).contains("DATA_INTEGRITY_VIOLATION constraint=unknown");
  }

  @Test
  void aCauseCycleEndsTheSearch() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);

    assertThat(GlobalExceptionHandler.constraintName(new RuntimeException(first))).isNull();
  }

  private static GlobalExceptionHandler handler(ConstraintErrorMapping mapping) {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerSingleton("mapping", mapping);
    return new GlobalExceptionHandler(
        new ApiErrorResponseWriter(Clock.systemUTC(), JsonMapper.builder().build()),
        beans.getBeanProvider(ConstraintErrorMapping.class),
        "10MB",
        "10MB");
  }

  private static DataIntegrityViolationException integrityViolation(String constraint) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new org.hibernate.exception.ConstraintViolationException(
            "could not execute statement", new SQLException("violation", "23505"), constraint));
  }

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("POST", "/things");
  }
}
