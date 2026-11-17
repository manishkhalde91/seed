package com.company.seed.common.limits;

import com.company.seed.common.web.RequestCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The request with its body stream cut off at the limit. */
final class LimitedBodyRequest extends HttpServletRequestWrapper {

  private static final Logger LOG = LoggerFactory.getLogger(RequestSizeFilter.class);

  private final long limit;
  private final String limitText;
  private LimitedInputStream stream;
  private BufferedReader reader;

  LimitedBodyRequest(HttpServletRequest request, long limit, String limitText) {
    super(request);
    this.limit = limit;
    this.limitText = limitText;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (stream == null) {
      stream = new LimitedInputStream(super.getInputStream(), limit, limitText);
    }
    return stream;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    if (reader == null) {
      Charset charset = RequestCharsets.of(getCharacterEncoding());
      reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
    return reader;
  }

  /** Counts the bytes read and throws {@link PayloadTooLargeException} past the limit. */
  private static final class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private final String limitText;
    private long count;

    LimitedInputStream(ServletInputStream delegate, long limit, String limitText) {
      this.delegate = delegate;
      this.limit = limit;
      this.limitText = limitText;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) {
        counted(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = delegate.read(buffer, offset, length);
      if (read > 0) {
        counted(read);
      }
      return read;
    }

    private void counted(int read) throws PayloadTooLargeException {
      count += read;
      if (count > limit) {
        LOG.debug("Request body passed the {} body limit while being read", limitText);
        throw new PayloadTooLargeException(limitText);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
