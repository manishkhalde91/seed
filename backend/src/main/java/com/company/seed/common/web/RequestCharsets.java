package com.company.seed.common.web;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/** The charset of a request body, for request wrappers that build their own {@code getReader()}. */
public final class RequestCharsets {

  private RequestCharsets() {}

  /**
   * The charset named by a request's character encoding (UTF-8 if none); an unknown or illegal name
   * is the Servlet API's {@link UnsupportedEncodingException}.
   */
  public static Charset of(String encoding) throws UnsupportedEncodingException {
    if (encoding == null) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(encoding);
    } catch (UnsupportedCharsetException | IllegalCharsetNameException ex) {
      UnsupportedEncodingException unsupported = new UnsupportedEncodingException(encoding);
      unsupported.initCause(ex);
      throw unsupported;
    }
  }
}
