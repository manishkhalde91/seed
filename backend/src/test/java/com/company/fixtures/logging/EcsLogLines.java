package com.company.fixtures.logging;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only reader for captured ECS JSON console output (one object per line); lines that are not
 * complete JSON objects are skipped.
 */
public final class EcsLogLines {

  /**
   * One log line: its logger, level, message, stack trace ({@code ""} when there is none) and the
   * whole JSON object.
   */
  public record Line(
      String logger, String level, String message, String stackTrace, JsonNode json) {

    /**
     * A member by dotted path, whether ECS wrote it flat ({@code "user.id"}) or nested ({@code
     * "user": {"id": …}}); {@code null} when absent.
     */
    public String field(String path) {
      JsonNode flat = json.get(path);
      if (flat != null && !flat.isMissingNode()) {
        return flat.asString();
      }
      JsonNode current = json;
      for (String segment : path.split("\\.")) {
        current = current.path(segment);
      }
      return current.isMissingNode() || current.isNull() ? null : current.asString();
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private EcsLogLines() {}

  /** The lines written by {@code logger}, in order. */
  public static List<Line> of(String output, String logger) {
    return all(output).stream().filter(line -> line.logger().equals(logger)).toList();
  }

  /** Every log line, in order. */
  public static List<Line> all(String output) {
    List<Line> lines = new ArrayList<>();
    for (String raw : output.split("\\R")) {
      if (!raw.startsWith("{")) {
        continue;
      }
      JsonNode line;
      try {
        line = JSON.readTree(raw);
      } catch (JacksonException incomplete) {
        // A line that only starts like JSON (e.g. interleaved output) is not a log record.
        continue;
      }
      lines.add(
          new Line(
              line.path("log").path("logger").asString(),
              line.path("log").path("level").asString(),
              line.path("message").asString(),
              line.path("error").path("stack_trace").asString(""),
              line));
    }
    return lines;
  }
}
