package com.company.seed.common.error.web;

import com.company.seed.common.api.FieldError;
import com.company.seed.common.validation.Sensitive;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.lang.reflect.Field;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;

/**
 * Turns Bean Validation results into {@link FieldError}s (errors-and-logging.md §3): dotted paths
 * with indices, the constraint's simple name as {@code code}, and a {@code rejectedValue} that is
 * left out for {@link Sensitive} fields and non-scalar values and truncated to 100 characters.
 */
final class FieldErrorMapper {

  static final int MAX_REJECTED_VALUE_LENGTH = 100;

  private static final Comparator<FieldError> ORDER =
      Comparator.comparing(FieldError::field, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(FieldError::code, Comparator.nullsFirst(Comparator.naturalOrder()));

  private FieldErrorMapper() {}

  /** From a {@code BindingResult} or {@code ParameterErrors}: field names are already dotted. */
  static List<FieldError> fromErrors(Errors errors) {
    List<FieldError> result = new ArrayList<>();
    for (org.springframework.validation.FieldError error : errors.getFieldErrors()) {
      ConstraintViolation<?> violation = violationOf(error);
      Object rejected =
          violation == null || isSensitive(violation) ? null : error.getRejectedValue();
      // Without a violation (e.g. typeMismatch) Spring's text names Java types and echoes input.
      String message = violation == null ? SafeMessages.INVALID_VALUE : error.getDefaultMessage();
      result.add(new FieldError(error.getField(), error.getCode(), message, safeValue(rejected)));
    }
    for (ObjectError error : errors.getGlobalErrors()) {
      result.add(new FieldError(null, error.getCode(), error.getDefaultMessage(), null));
    }
    return sorted(result);
  }

  /** From Spring MVC's built-in method validation ({@code HandlerMethodValidationException}). */
  static List<FieldError> fromMethodValidation(MethodValidationResult validation) {
    List<FieldError> result = new ArrayList<>();
    for (ParameterValidationResult parameter : validation.getParameterValidationResults()) {
      if (parameter instanceof ParameterErrors errors) {
        String element = containerPosition(parameter);
        for (FieldError error : fromErrors(errors)) {
          result.add(element == null ? error : prefixed(element, error));
        }
        continue;
      }
      String field = parameterPath(parameter);
      Object rejected = safeValue(parameter.getArgument());
      for (MessageSourceResolvable error : parameter.getResolvableErrors()) {
        result.add(new FieldError(field, lastCode(error), error.getDefaultMessage(), rejected));
      }
    }
    for (MessageSourceResolvable error : validation.getCrossParameterValidationResults()) {
      result.add(new FieldError(null, lastCode(error), error.getDefaultMessage(), null));
    }
    return sorted(result);
  }

  /** From a {@code ConstraintViolationException}; the leading method segment is dropped. */
  static List<FieldError> fromViolations(Collection<ConstraintViolation<?>> violations) {
    List<FieldError> result = new ArrayList<>();
    for (ConstraintViolation<?> violation : violations) {
      result.add(fromViolation(violation));
    }
    return sorted(result);
  }

  private static FieldError fromViolation(ConstraintViolation<?> violation) {
    Object rejected = isSensitive(violation) ? null : violation.getInvalidValue();
    return new FieldError(
        fieldPath(violation.getPropertyPath()),
        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
        violation.getMessage(),
        safeValue(rejected));
  }

  /**
   * Renders {@code create.request.items[2].name} as {@code request.items[2].name}: method,
   * return-value and container-element names are skipped, iterable positions become indices.
   */
  static String fieldPath(Path path) {
    StringBuilder field = new StringBuilder();
    for (Path.Node node : path) {
      if (node.isInIterable() && !field.isEmpty()) {
        Object position = node.getIndex() != null ? node.getIndex() : node.getKey();
        field.append('[').append(position != null ? position : "").append(']');
      }
      ElementKind kind = node.getKind();
      String name = node.getName();
      if ((kind == ElementKind.PROPERTY || kind == ElementKind.PARAMETER)
          && name != null
          && !name.startsWith("<")) {
        if (!field.isEmpty()) {
          field.append('.');
        }
        field.append(name);
      }
    }
    return field.isEmpty() ? null : field.toString();
  }

  /** {@code @Sensitive} is checked on the DTO field behind the violated path. */
  private static boolean isSensitive(ConstraintViolation<?> violation) {
    Object leafBean = violation.getLeafBean();
    Path.Node last = null;
    String property = null;
    for (Path.Node node : violation.getPropertyPath()) {
      last = node;
      if (node.getKind() == ElementKind.PROPERTY) {
        property = node.getName();
      } else if (node.getKind() == ElementKind.PARAMETER) {
        property = null;
      }
    }
    if (leafBean == null || property == null || last.getKind() == ElementKind.BEAN) {
      return false;
    }
    Field field = ReflectionUtils.findField(leafBean.getClass(), property);
    return field != null && field.isAnnotationPresent(Sensitive.class);
  }

  /** Only scalars are echoed back; strings are cut to {@value #MAX_REJECTED_VALUE_LENGTH} chars. */
  static Object safeValue(Object value) {
    if (value instanceof CharSequence text) {
      if (text.length() <= MAX_REJECTED_VALUE_LENGTH) {
        return text.toString();
      }
      int end = MAX_REJECTED_VALUE_LENGTH;
      if (Character.isHighSurrogate(text.charAt(end - 1))) {
        end--; // never split a surrogate pair
      }
      return text.subSequence(0, end).toString();
    }
    if (value instanceof Enum<?> constant) {
      return constant.name();
    }
    if (value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof UUID
        || value instanceof TemporalAccessor) {
      return value;
    }
    return null;
  }

  private static ConstraintViolation<?> violationOf(ObjectError error) {
    return error.contains(ConstraintViolation.class)
        ? error.unwrap(ConstraintViolation.class)
        : null;
  }

  /** {@code [2]} or {@code [key]} for an element of a container parameter, else null. */
  private static String containerPosition(ParameterValidationResult parameter) {
    if (parameter.getContainerIndex() != null) {
      return "[" + parameter.getContainerIndex() + "]";
    }
    return parameter.getContainerKey() != null ? "[" + parameter.getContainerKey() + "]" : null;
  }

  private static FieldError prefixed(String element, FieldError error) {
    String field = error.field() == null ? element : element + "." + error.field();
    return new FieldError(field, error.code(), error.message(), error.rejectedValue());
  }

  /** The parameter name, with the index or key when the constraint is on a container element. */
  private static String parameterPath(ParameterValidationResult parameter) {
    String name = parameter.getMethodParameter().getParameterName();
    if (parameter.getContainerIndex() != null) {
      return name + "[" + parameter.getContainerIndex() + "]";
    }
    return parameter.getContainerKey() != null
        ? name + "[" + parameter.getContainerKey() + "]"
        : name;
  }

  private static String lastCode(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
  }

  private static List<FieldError> sorted(List<FieldError> errors) {
    errors.sort(ORDER);
    return errors;
  }
}
