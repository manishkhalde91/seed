package com.company.seed.common.limits;

import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.error.web.Causes;
import com.company.seed.common.logging.LogSafe;
import com.company.seed.common.logging.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request body limit, {@code seed.limits.max-body-size} (security.md §6), for every request
 * except {@code multipart/form-data} (uploads have the servlet multipart limits, {@code
 * SEED_MAX_UPLOAD_SIZE}). Every other multipart subtype has this limit.
 *
 * <ul>
 *   <li>A {@code Content-Length} over the limit is answered 413 {@code PAYLOAD_TOO_LARGE} ("Request
 *       body exceeds 1MB") at once: nothing reads the body and nothing after this filter runs.
 *   <li>A form post ({@code application/x-www-form-urlencoded}) is parsed by the container under
 *       {@code server.tomcat.max-http-form-post-size} (the same limit), which would drop the
 *       parameters of a larger body sent without a length; this filter parses it first and answers
 *       413 when the container finds it too large.
 *   <li>Otherwise the body stream is wrapped, so reading past the limit (a body sent without a
 *       length) throws {@link PayloadTooLargeException}. A handler's read is answered 413 by {@code
 *       GlobalExceptionHandler}; when a filter's read (such as {@code IdempotencyFilter}'s) lets it
 *       escape the chain, this filter answers 413 itself, unless the response is already committed.
 *       ({@code JsonErrorController} answers it 413 too, should it reach {@code /error}.)
 * </ul>
 *
 * <p>Runs after {@link RequestLoggingFilter} and the security module's browser-policy filter (CORS
 * and security headers, at {@code RequestLoggingFilter.ORDER + 1}), before the security chain: the
 * check needs no caller, and it keeps huge bodies away from the token parser and everything after
 * it. Its 413 carries the correlation ID, the CORS and security headers, and gets a summary line.
 */
@Component
@Order(RequestSizeFilter.ORDER)
@EnableConfigurationProperties(LimitsProperties.class)
public class RequestSizeFilter extends OncePerRequestFilter {

  /** Filter order: after {@link RequestLoggingFilter} and the browser-policy filter. */
  public static final int ORDER = RequestLoggingFilter.ORDER + 2;

  private static final Logger LOG = LoggerFactory.getLogger(RequestSizeFilter.class);
  private static final int TOO_LARGE = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;

  private final LimitsProperties properties;
  private final ApiErrorResponseWriter errorWriter;

  public RequestSizeFilter(LimitsProperties properties, ApiErrorResponseWriter errorWriter) {
    this.properties = properties;
    this.errorWriter = errorWriter;
  }

  /** The client-safe 413 message naming the limit. */
  static String message(String limit) {
    return "Request body exceeds " + limit;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return isContentType(request, MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long limit = properties.maxBodyBytes();
    String limitText = properties.maxBodySizeText();
    long length = request.getContentLengthLong();
    if (length > limit) {
      reject(request, response, "Content-Length " + length + " is over", message(limitText));
      return;
    }
    if (isFormPostTooLarge(request)) {
      reject(request, response, "form body is over", message(limitText));
      return;
    }
    try {
      chain.doFilter(new LimitedBodyRequest(request, limit, limitText), response);
    } catch (IOException | ServletException | RuntimeException ex) {
      Optional<PayloadTooLargeException> tooLarge =
          Causes.findCause(ex, PayloadTooLargeException.class);
      if (tooLarge.isEmpty() || response.isCommitted()) {
        throw ex;
      }
      // Read past the limit by a filter (a handler's read is answered by GlobalExceptionHandler).
      // Answered here, so the container does not log a client's oversized body as a server error.
      reject(request, response, "body was read past", tooLarge.get().getSafeMessage());
    }
  }

  /**
   * Parses a form post's parameters now: whether the container found the body larger than its
   * form-post limit. Any other parse failure is left for whoever reads the parameters next.
   */
  private static boolean isFormPostTooLarge(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())
        || !isContentType(request, MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
      return false;
    }
    try {
      request.getParameterMap();
      return false;
    } catch (InvalidParameterException ex) {
      return ex.getErrorCode() == TOO_LARGE;
    }
  }

  private static boolean isContentType(HttpServletRequest request, String type) {
    String contentType = request.getContentType();
    return contentType != null
        && contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT).equals(type);
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, String reason, String message)
      throws IOException {
    LOG.info(
        "{} {} rejected: {} the {} body limit",
        LogSafe.clean(request.getMethod()),
        LogSafe.clean(request.getRequestURI()),
        reason,
        properties.maxBodySizeText());
    errorWriter.write(request, response, TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, message);
  }
}
