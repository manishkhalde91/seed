package com.company.seed.common.logging;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks sensitive members of every structured (JSON) log line: a member whose last path segment,
 * lowercased and stripped of every non-alphanumeric character, ends with {@code password}, {@code
 * secret}, {@code token}, {@code authorization}, {@code apikey}, {@code privatekey} or {@code
 * email} (so {@code accessToken}, {@code refresh_token}, {@code x-api-key} and {@code userEmail}
 * too), wherever it is nested, is written as {@value #MASK}. Registered through {@code
 * logging.structured.json.customizer}, so Spring Boot creates it with the default constructor.
 *
 * <p>Only structured output is masked; readable {@code local} lines are not. Message text is never
 * masked: the code never logs headers, bodies or raw values in the first place.
 */
public class SensitiveMemberMaskingCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {

  /** What a sensitive value is replaced with. */
  public static final String MASK = "******";

  private static final List<String> SENSITIVE_SUFFIXES =
      List.of("password", "secret", "token", "authorization", "apikey", "privatekey", "email");
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

  @Override
  public void customize(JsonWriter.Members<Object> members) {
    members.applyingValueProcessor(
        JsonWriter.ValueProcessor.<Object>of(value -> MASK)
            .whenHasPath(SensitiveMemberMaskingCustomizer::isSensitive));
  }

  static boolean isSensitive(MemberPath path) {
    String name = path.name();
    if (name == null) {
      return false;
    }
    String normalized = NON_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    return SENSITIVE_SUFFIXES.stream().anyMatch(normalized::endsWith);
  }
}
