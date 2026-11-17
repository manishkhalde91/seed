package com.company.seed.security;

import com.company.seed.common.actor.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the authenticated token's {@code sub} into the MDC as {@value CurrentActor#MDC_KEY}, so
 * every later log line of the request carries it, the request summary line included
 * (errors-and-logging.md §4). Runs in the security chain right after bearer-token authentication,
 * on every dispatch (the ERROR and ASYNC re-dispatches restore the context from the request). A
 * request without a valid token gets no entry. The entry is never removed here: {@code
 * CorrelationIdFilter} clears it with the request's other MDC keys. Not a bean, so it runs only
 * inside the security chain.
 */
final class AuthenticatedUserMdcFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
        && jwt.isAuthenticated()
        && jwt.getToken().getSubject() != null
        && !jwt.getToken().getSubject().isBlank()) {
      MDC.put(CurrentActor.MDC_KEY, jwt.getToken().getSubject());
    }
    chain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
