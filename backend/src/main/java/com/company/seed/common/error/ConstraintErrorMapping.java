package com.company.seed.common.error;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps database constraint names to the error codes of a module (errors-and-logging.md §2). A
 * module declares one bean per mapping; a {@code DataIntegrityViolationException} on a mapped
 * constraint becomes {@code 409} with the mapped code, any other one {@code 500
 * DATA_INTEGRITY_VIOLATION}. Two beans that map the same constraint name fail startup.
 *
 * <p>The lookup is by constraint name alone (any schema prefix is dropped), so constraint names
 * must be unique across schemas, as the naming convention makes them. It applies to violations that
 * reach the handler through JPA/Hibernate, which reports the violated constraint's name.
 *
 * <pre>{@code
 * @Bean
 * ConstraintErrorMapping sampleRecordConstraints() {
 *   return ConstraintErrorMapping.of("uq_sample_record__name", "SAMPLE_RECORD_ALREADY_EXISTS");
 * }
 * }</pre>
 *
 * @param errorCodes error code by constraint name; names are compared case-insensitively
 */
public record ConstraintErrorMapping(Map<String, String> errorCodes) {

  public ConstraintErrorMapping {
    Objects.requireNonNull(errorCodes, "errorCodes");
    for (Map.Entry<String, String> entry : errorCodes.entrySet()) {
      if (isBlank(entry.getKey()) || isBlank(entry.getValue())) {
        throw new IllegalArgumentException(
            "Constraint names and error codes must not be blank: " + errorCodes);
      }
    }
    errorCodes = Map.copyOf(errorCodes);
  }

  public static ConstraintErrorMapping of(String constraintName, String errorCode) {
    return new ConstraintErrorMapping(Map.of(constraintName, errorCode));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** The normalized key a constraint name is looked up by. */
  public static String normalize(String constraintName) {
    return constraintName.toLowerCase(Locale.ROOT);
  }
}
