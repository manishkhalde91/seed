package com.company.seed.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogSafeTest {

  @Test
  void nullStaysNull() {
    assertThat(LogSafe.clean(null)).isNull();
  }

  @Test
  void replacesCarriageReturnAndLineFeed() {
    assertThat(LogSafe.clean("a\r\nb")).isEqualTo("a__b");
  }

  @Test
  void replacesUnicodeLineAndParagraphSeparators() {
    assertThat(LogSafe.clean("a\u2028b")).isEqualTo("a_b");
    assertThat(LogSafe.clean("a\u2029b")).isEqualTo("a_b");
  }

  @ParameterizedTest(name = "U+{0}")
  @CsvSource({"0000", "0009", "001B", "001F", "007F", "0080", "0085", "009F"})
  void replacesC0DelAndC1Controls(String hex) {
    char control = (char) Integer.parseInt(hex, 16);

    assertThat(LogSafe.clean("a" + control + "b")).isEqualTo("a_b");
  }

  @ParameterizedTest(name = "U+{0}")
  @CsvSource({"202A", "202B", "202C", "202D", "202E", "2066", "2067", "2068", "2069"})
  void replacesBidirectionalControls(String hex) {
    char control = (char) Integer.parseInt(hex, 16);

    assertThat(LogSafe.clean("a" + control + "b")).isEqualTo("a_b");
  }

  @Test
  void keepsPrintableCharacters() {
    String printable = "GET /probe/items/{id} é ü 漢字   ~";

    assertThat(LogSafe.clean(printable)).isEqualTo(printable);
  }

  @Test
  void truncatesTo200Characters() {
    String cleaned = LogSafe.clean("x".repeat(250));

    assertThat(cleaned).hasSize(200).isEqualTo("x".repeat(200));
  }

  @Test
  void neverSplitsASurrogatePairWhenTruncating() {
    String cleaned = LogSafe.clean("x".repeat(199) + "😀" + "tail");

    assertThat(cleaned).isEqualTo("x".repeat(199));
  }
}
