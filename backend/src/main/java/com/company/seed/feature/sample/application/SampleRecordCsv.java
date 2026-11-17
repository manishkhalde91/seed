package com.company.seed.feature.sample.application;

import com.company.seed.feature.sample.domain.SampleRecord;
import java.util.List;

/**
 * DEMO ONLY. The export file: RFC 4180 CSV with a header row, {@code createdAt} in ISO-8601 UTC. A
 * field is quoted when it holds a comma, quote, CR or LF; quotes inside it are doubled. A text that
 * a spreadsheet would read as a formula (it starts with {@code = + - @}, tab or CR) gets a leading
 * {@code '} (OWASP CSV injection).
 */
final class SampleRecordCsv {

  static final String HEADER = "id,name,description,createdAt";

  private static final String FORMULA_STARTS = "=+-@\t\r";

  private SampleRecordCsv() {}

  static String of(List<SampleRecord> records) {
    StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
    for (SampleRecord sampleRecord : records) {
      csv.append(sampleRecord.id())
          .append(',')
          .append(field(sampleRecord.name()))
          .append(',')
          .append(field(sampleRecord.description()))
          .append(',')
          .append(sampleRecord.createdAt())
          .append("\r\n");
    }
    return csv.toString();
  }

  static String field(String value) {
    if (value == null) {
      return "";
    }
    if (!value.isEmpty() && FORMULA_STARTS.indexOf(value.charAt(0)) >= 0) {
      value = "'" + value;
    }
    if (value.chars().anyMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n')) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }
}
