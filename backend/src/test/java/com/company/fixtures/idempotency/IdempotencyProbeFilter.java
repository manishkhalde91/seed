package com.company.fixtures.idempotency;

import com.company.seed.common.idempotency.IdempotencyFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Test-only filter right after {@link IdempotencyFilter}: counts every probe request that got past
 * it (so a request rejected by Bean Validation still counts as a handler invocation). It throws for
 * {@value #ESCAPE}, an exception escaping the chain, and answers {@value #SEND_ERROR} with {@code
 * sendError(403)}, an answer the container writes.
 */
public class IdempotencyProbeFilter extends OncePerRequestFilter implements Ordered {

  public static final String ESCAPE = IdempotencyProbeController.BASE + "/escape";
  public static final String SEND_ERROR = IdempotencyProbeController.BASE + "/send-error";

  private final IdempotencyProbe probe;

  public IdempotencyProbeFilter(IdempotencyProbe probe) {
    this.probe = probe;
  }

  @Override
  public int getOrder() {
    return IdempotencyFilter.ORDER + 1;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(IdempotencyProbeController.BASE + "/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    probe.invoked();
    switch (request.getRequestURI()) {
      case ESCAPE -> throw new IllegalStateException("probe filter failure");
      case SEND_ERROR -> response.sendError(HttpServletResponse.SC_FORBIDDEN);
      default -> chain.doFilter(request, response);
    }
  }
}
