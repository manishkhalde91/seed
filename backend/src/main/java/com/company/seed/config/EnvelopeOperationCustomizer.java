package com.company.seed.config;

import com.company.seed.common.api.ApiErrorResponse;
import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.PageMeta;
import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.Sortable;
import com.company.seed.common.logging.CorrelationIdFilter;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents every operation with the envelope (api-standards.md §3, §7, §8):
 *
 * <ul>
 *   <li>each 2xx response is {@code ApiResponse} with {@code data} typed as the handler's {@code T}
 *       (an {@code allOf} of the {@code ApiResponse} schema and the typed {@code data}); a paged
 *       list (a handler taking a {@link PageQuery}) also requires {@code pagination};
 *   <li>each operation lists {@code ApiErrorResponse} for 400, 401, 403, 404, 409, 422 and 500;
 *   <li>each operation takes the optional {@code X-Correlation-Id} request header, and every
 *       response carries it;
 *   <li>a {@link PageQuery} parameter is documented as {@code page}, {@code size} and the
 *       repeatable {@code sort}, naming the endpoint's {@link Sortable} fields.
 * </ul>
 */
final class EnvelopeOperationCustomizer implements OperationCustomizer {

  static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

  private static final String SCHEMAS = "#/components/schemas/";
  private static final String CORRELATION_ID_PATTERN =
      "^" + CorrelationIdFilter.TOKEN.pattern() + "$";

  /** The documented errors of every operation, in status order. */
  private static final Map<String, String> ERRORS = errors();

  private static Map<String, String> errors() {
    Map<String, String> errors = new LinkedHashMap<>();
    errors.put("400", "Validation failed or malformed request");
    errors.put("401", "Missing, invalid or expired token");
    errors.put("403", "Not allowed for the caller's roles");
    errors.put("404", "Resource missing or deleted");
    errors.put("409", "Conflict with the stored state (duplicate, stale version, state)");
    errors.put("422", "Breaks a domain rule");
    errors.put("500", "Unexpected error; quote the correlation ID");
    return errors;
  }

  @Override
  public Operation customize(Operation operation, HandlerMethod handlerMethod) {
    MethodParameter pageQuery = pageQueryParameter(handlerMethod);
    if (pageQuery != null) {
      addPagingParameters(operation, pageQuery.getParameterAnnotation(Sortable.class));
    }
    operation.addParametersItem(correlationIdParameter());
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    Schema<?> data = dataSchema(handlerMethod);
    operation
        .getResponses()
        .forEach(
            (status, response) -> {
              if (status.startsWith("2")) {
                response.setContent(json(envelope(data, pageQuery != null, response.getContent())));
              }
            });
    ERRORS.forEach(
        (status, description) ->
            operation
                .getResponses()
                .computeIfAbsent(
                    status,
                    code ->
                        new io.swagger.v3.oas.models.responses.ApiResponse()
                            .description(description)
                            .content(json(ref(ApiErrorResponse.class)))));
    operation
        .getResponses()
        .forEach(
            (status, response) -> {
              response.addHeaderObject(CorrelationIdFilter.HEADER, correlationIdHeader());
              if (status.equals("201")) {
                response.addHeaderObject(
                    HttpHeaders.LOCATION,
                    new Header()
                        .description("URL of the created resource")
                        .schema(new StringSchema()));
              }
            });
    return operation;
  }

  /**
   * The {@code data} schema of the handler's {@code ResponseEntity<ApiResponse<T>>}: {@code T} as a
   * reference (lists as arrays of references), {@code null} for {@code Void}; {@code null} when the
   * handler returns something else.
   */
  private static Schema<?> dataSchema(HandlerMethod handlerMethod) {
    ResolvableType returnType = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
    ResolvableType body = returnType.getGeneric(0);
    if (!ApiResponse.class.equals(body.resolve())) {
      return null;
    }
    ResolvableType data = body.getGeneric(0);
    if (Void.class.equals(data.resolve())) {
      return new Schema<>().types(Set.of("null"));
    }
    Type type = data.getType();
    ResolvedSchema resolved =
        ModelConverters.getInstance(true)
            .readAllAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
    return resolved == null ? null : resolved.schema;
  }

  /**
   * {@code ApiResponse} with a typed {@code data}; when the type is unknown, the generated schema
   * of the existing content is kept.
   */
  private static Schema<?> envelope(Schema<?> data, boolean paged, Content existing) {
    if (data == null) {
      return existing == null || existing.isEmpty()
          ? ref(ApiResponse.class)
          : existing.values().iterator().next().getSchema();
    }
    ObjectSchema typed = new ObjectSchema();
    typed.addProperty("data", data);
    typed.addRequiredItem("data");
    if (paged) {
      typed.addProperty("pagination", ref(PageMeta.class));
      typed.addRequiredItem("pagination");
    }
    return new Schema<>().allOf(List.of(ref(ApiResponse.class), typed));
  }

  private static MethodParameter pageQueryParameter(HandlerMethod handlerMethod) {
    return Arrays.stream(handlerMethod.getMethodParameters())
        .filter(parameter -> PageQuery.class.equals(parameter.getParameterType()))
        .findFirst()
        .orElse(null);
  }

  private static void addPagingParameters(Operation operation, Sortable sortable) {
    operation.addParametersItem(
        new Parameter()
            .in("query")
            .name(PageQuery.PAGE)
            .required(false)
            .description("0-based page index")
            .schema(
                new IntegerSchema()
                    .minimum(java.math.BigDecimal.ZERO)
                    ._default(PageQuery.DEFAULT_PAGE)));
    operation.addParametersItem(
        new Parameter()
            .in("query")
            .name(PageQuery.SIZE)
            .required(false)
            .description("Page size")
            .schema(
                new IntegerSchema()
                    .minimum(java.math.BigDecimal.ONE)
                    .maximum(java.math.BigDecimal.valueOf(PageQuery.MAX_SIZE))
                    ._default(PageQuery.DEFAULT_SIZE)));
    List<String> fields =
        sortable == null ? List.of() : List.copyOf(new TreeSet<>(List.of(sortable.fields())));
    String defaultSort = sortable == null ? "" : String.join("; ", sortable.defaultSort());
    operation.addParametersItem(
        new Parameter()
            .in("query")
            .name(PageQuery.SORT)
            .required(false)
            .explode(true)
            .description(
                fields.isEmpty()
                    ? "Not sortable"
                    : "Repeatable field[,asc|desc] (asc when omitted). Fields: "
                        + String.join(", ", fields)
                        + (defaultSort.isEmpty() ? "" : ". Default: " + defaultSort))
            .schema(new ArraySchema().items(new StringSchema())));
  }

  private static Parameter correlationIdParameter() {
    return new Parameter()
        .in("header")
        .name(CorrelationIdFilter.HEADER)
        .required(false)
        .description("Correlation ID to use for this request; replaced by a new one when invalid")
        .schema(new StringSchema().pattern(CORRELATION_ID_PATTERN));
  }

  private static Header correlationIdHeader() {
    return new Header()
        .description("The request's correlation ID, also in the body's correlationId")
        .schema(new StringSchema());
  }

  private static Content json(Schema<?> schema) {
    return new Content().addMediaType(JSON, new MediaType().schema(schema));
  }

  private static Schema<?> ref(Class<?> type) {
    return new Schema<>().$ref(SCHEMAS + type.getSimpleName());
  }
}
