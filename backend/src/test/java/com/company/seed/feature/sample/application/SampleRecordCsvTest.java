package com.company.seed.feature.sample.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.seed.feature.sample.domain.SampleRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DEMO ONLY. The export file is RFC 4180 CSV with {@code createdAt} in ISO-8601 UTC. */
class SampleRecordCsvTest {

  @Test
  void fieldsWithCommasQuotesOrLineBreaksAreQuotedAndQuotesDoubled() {
    assertThat(SampleRecordCsv.field("plain")).isEqualTo("plain");
    assertThat(SampleRecordCsv.field("a,b")).isEqualTo("\"a,b\"");
    assertThat(SampleRecordCsv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(SampleRecordCsv.field("two\nlines")).isEqualTo("\"two\nlines\"");
    assertThat(SampleRecordCsv.field("cr\rhere")).isEqualTo("\"cr\rhere\"");
    assertThat(SampleRecordCsv.field(null)).isEmpty();
  }

  @Test
  void aTextASpreadsheetWouldReadAsAFormulaGetsALeadingApostrophe() {
    assertThat(SampleRecordCsv.field("=1+1")).isEqualTo("'=1+1");
    assertThat(SampleRecordCsv.field("+1")).isEqualTo("'+1");
    assertThat(SampleRecordCsv.field("-1")).isEqualTo("'-1");
    assertThat(SampleRecordCsv.field("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(SampleRecordCsv.field("\tx")).isEqualTo("'\tx");
    assertThat(SampleRecordCsv.field("\rx")).isEqualTo("\"'\rx\"");
    assertThat(SampleRecordCsv.field("=HYPERLINK(\"a\",\"b\")"))
        .isEqualTo("\"'=HYPERLINK(\"\"a\"\",\"\"b\"\")\"");
    assertThat(SampleRecordCsv.field("a=b")).isEqualTo("a=b");
    assertThat(SampleRecordCsv.field("")).isEmpty();
  }

  @Test
  void theFileHasAHeaderAndOneCrlfTerminatedRowPerRecord() {
    UUID id = UUID.fromString("01934b6e-0000-7000-8000-000000000001");
    Instant createdAt = Instant.parse("2026-11-17T09:00:00Z");
    SampleRecord sampleRecord =
        SampleRecord.stored(
            id, "Name, with comma", null, 0L, createdAt, "user:u", createdAt, "user:u", null, null);

    assertThat(SampleRecordCsv.of(List.of(sampleRecord)))
        .isEqualTo(
            "id,name,description,createdAt\r\n"
                + id
                + ",\"Name, with comma\",,2026-11-17T09:00:00Z\r\n");
  }
}
