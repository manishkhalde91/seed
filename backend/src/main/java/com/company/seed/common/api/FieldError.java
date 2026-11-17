package com.company.seed.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One rejected field of a validation failure.
 *
 * @param field dotted path with indices ({@code items[2].name}), or the parameter name
 * @param code the constraint's simple name ({@code NotBlank}); the frontend keys its messages on it
 * @param message text from {@code ValidationMessages.properties}
 * @param rejectedValue the rejected value, truncated to 100 characters; left out for {@code
 *     Sensitive} fields and for values that are not scalars
 */
public record FieldError(
    String field,
    String code,
    String message,
    @JsonInclude(JsonInclude.Include.NON_NULL) Object rejectedValue) {}
