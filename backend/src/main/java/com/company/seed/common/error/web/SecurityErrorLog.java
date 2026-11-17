package com.company.seed.common.error.web;

import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.logging.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The one log line of a security answer (401, 403, or 503 when the issuer cannot be reached),
 * shared by the filter chain's handlers and {@link GlobalExceptionHandler}: method, path, status,
 * error code, subject, exception type and correlation ID. The path and subject are {@link
 * LogSafe}-cleaned; the exception's message (which may name the token check that failed) is never
 * logged, and the stack trace only at ERROR.
 */
public final class SecurityErrorLog {

  /** The subject logged when nobody is authenticated. */
  public static final String ANONYMOUS = "anonymous";

  private SecurityErrorLog() {}

  /** Writes the line to {@code log} at {@code level}. */
  public static void log(
      Logger log,
      Level level,
      HttpServletRequest request,
      int status,
      String errorCode,
      Throwable ex) {
    String format = "{} {} -> {} {} subject={} ({}) [correlationId={}]";
    Object[] arguments = {
      LogSafe.clean(request.getMethod()),
      LogSafe.clean(request.getRequestURI()),
      status,
      errorCode,
      subject(),
      ex.getClass().getName(),
      MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY)
    };
    if (level == Level.ERROR) {
      log.atError().setCause(ex).log(format, arguments);
    } else {
      log.atLevel(level).log(format, arguments);
    }
  }

  /** The authenticated subject (the token's {@code sub}), or {@value #ANONYMOUS}. */
  public static String subject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return isAuthenticated(authentication) ? LogSafe.clean(authentication.getName()) : ANONYMOUS;
  }

  /** Whether a real (not anonymous) caller is authenticated on this thread. */
  public static boolean isAuthenticated() {
    return isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
  }

  private static boolean isAuthenticated(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null;
  }
}
