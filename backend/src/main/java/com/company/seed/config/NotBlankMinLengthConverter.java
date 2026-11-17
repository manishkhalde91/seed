package com.company.seed.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;

/**
 * Gives every {@code @NotBlank} string property {@code minLength: 1}. swagger-core applies the
 * {@code @Size} minimum ({@code 0} by default) after any property customizer, so this converter
 * corrects the finished model of each class it sees.
 */
final class NotBlankMinLengthConverter implements ModelConverter {

  private static final String SCHEMAS = "#/components/schemas/";

  @Override
  public Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    Class<?> raw = rawClass(type.getType());
    if (schema == null || raw == null) {
      return schema;
    }
    Schema<?> model =
        schema.get$ref() != null && schema.get$ref().startsWith(SCHEMAS)
            ? context.getDefinedModels().get(schema.get$ref().substring(SCHEMAS.length()))
            : schema;
    if (model == null || model.getProperties() == null) {
      return schema;
    }
    for (Field field : raw.getDeclaredFields()) {
      Schema<?> property = model.getProperties().get(field.getName());
      if (property != null
          && field.isAnnotationPresent(NotBlank.class)
          && (property.getMinLength() == null || property.getMinLength() < 1)) {
        property.setMinLength(1);
      }
    }
    return schema;
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> c) {
      return c;
    }
    return type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c ? c : null;
  }
}
