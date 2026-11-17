package com.company.seed.common.logging;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.ids.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (api-standards.md §8). A valid {@value #HEADER} header
 * ({@code [A-Za-z0-9._:-]{1,64}}) is kept; anything else is replaced by a new UUIDv7 and never
 * logged. The ID goes into the MDC under {@link ApiResponses#CORRELATION_ID_MDC_KEY}, into the
 * response header before the chain runs, and into a request attribute, so the ERROR and ASYNC
 * re-dispatches of the same request restore the same ID.
 *
 * <p>It also owns the lifetime of the request's other MDC entries: {@value CurrentActor#MDC_KEY} is
 * removed when each dispatch starts (the security chain puts it back for an authenticated request,
 * so a value left on a pooled thread never reaches another request) and cleared with the ID at the
 * end.
 *
 * <p>First of the application's own filters and before the security chain; Boot's framework filters
 * that sort earlier (such as character encoding) may run before it.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Filter order: first of the application's filters, before Spring Security's chain (-100). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  /** Request and response header carrying the ID. */
  public static final String HEADER = "X-Correlation-Id";

  /** Request attribute holding the request's ID across dispatches. */
  public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

  /**
   * The format of a caller-supplied header token, shared by {@value #HEADER} and {@code
   * Idempotency-Key} (api-standards.md §1, §8).
   */
  public static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  private final IdGenerator idGenerator;

  public CorrelationIdFilter(IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = correlationIdOf(request);
    request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    MDC.remove(CurrentActor.MDC_KEY);
    MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
    if (!response.isCommitted()) {
      response.setHeader(HEADER, correlationId);
    }
    // Cleared only on normal return: when the chain throws, the container logs the exception
    // (StandardWrapperValve) after this filter, so the ID stays for that line. The ERROR
    // re-dispatch re-puts and clears it; the next request on the thread overwrites it.
    chain.doFilter(request, response);
    MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY);
    MDC.remove(CurrentActor.MDC_KEY);
  }

  /** The ID of an earlier dispatch of this request, else a valid header, else a new UUIDv7. */
  private String correlationIdOf(HttpServletRequest request) {
    if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String earlier) {
      return earlier;
    }
    String supplied = request.getHeader(HEADER);
    if (supplied != null && TOKEN.matcher(supplied).matches()) {
      return supplied;
    }
    return idGenerator.newId().toString();
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }
}
