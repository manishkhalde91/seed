package com.company.seed.common.error.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FieldErrorMapperTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  record Tagged(Set<@NotBlank String> tags) {}

  @BeforeAll
  static void createValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void setElementPathHasEmptyBrackets() {
    Set<ConstraintViolation<Tagged>> violations = validator.validate(new Tagged(Set.of(" ")));

    assertThat(violations).hasSize(1);
    assertThat(FieldErrorMapper.fieldPath(violations.iterator().next().getPropertyPath()))
        .isEqualTo("tags[]");
  }

  @Test
  void truncationNeverSplitsASurrogatePair() {
    String value = "a".repeat(99) + "😀" + "tail";

    Object truncated = FieldErrorMapper.safeValue(value);

    assertThat(truncated).isEqualTo("a".repeat(99));
  }

  @Test
  void truncationKeepsShortValuesAndCutsLongOnesAt100() {
    assertThat(FieldErrorMapper.safeValue("short")).isEqualTo("short");
    assertThat(FieldErrorMapper.safeValue("x".repeat(150))).isEqualTo("x".repeat(100));
  }
}
