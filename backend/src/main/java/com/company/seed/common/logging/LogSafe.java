package com.company.seed.common.logging;

/**
 * Makes a caller-supplied value safe to put in a log line (CWE-117): control characters and line
 * separators become {@code _}, and the result is at most {@value #MAX_LENGTH} characters.
 */
public final class LogSafe {

  /** The longest value {@link #clean(String)} returns. */
  public static final int MAX_LENGTH = 200;

  private static final char REPLACEMENT = '_';

  private LogSafe() {}

  /**
   * {@code null} stays {@code null}. C0 controls, DEL, C1 controls (U+0080 to U+009F), U+2028,
   * U+2029 and the bidirectional controls U+202A to U+202E and U+2066 to U+2069 (which could
   * visually reorder a viewed line) become {@code _}; the result is cut to {@value #MAX_LENGTH}
   * characters, never splitting a surrogate pair.
   */
  public static String clean(String value) {
    if (value == null) {
      return null;
    }
    int length = Math.min(value.length(), MAX_LENGTH);
    if (length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))) {
      length--;
    }
    StringBuilder cleaned = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      cleaned.append(isUnsafe(c) ? REPLACEMENT : c);
    }
    return cleaned.toString();
  }

  private static boolean isUnsafe(char c) {
    return c < 0x20
        || (c >= 0x7F && c <= 0x9F)
        || c == '\u2028'
        || c == '\u2029'
        || (c >= '\u202A' && c <= '\u202E')
        || (c >= '\u2066' && c <= '\u2069');
  }
}
