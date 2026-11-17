package com.company.seed.common.operations.infrastructure.ws;

import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.error.web.Causes;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/**
 * The STOMP {@code ERROR} codes of api-standards.md §9 and the one way an {@code ERROR} frame is
 * built. A frame's {@code message} header is exactly its code, with no details and no body; the
 * server closes the connection after sending it.
 *
 * <p>{@value #INTERNAL_ERROR} is the code of an unexpected failure (logged once at ERROR, with the
 * session's correlation ID); it is the one code beyond the §9 list.
 */
public final class StompErrors {

  /** CONNECT without a valid token, or any other frame before a successful CONNECT. */
  public static final String UNAUTHORIZED = "unauthorized";

  /** A destination the session may not subscribe to, and every {@code SEND}. */
  public static final String FORBIDDEN_DESTINATION = "forbidden-destination";

  /** The session's token expired while it was connected. */
  public static final String TOKEN_EXPIRED = "token-expired";

  /** A subscription beyond the session's limit. */
  public static final String TOO_MANY_SUBSCRIPTIONS = "too-many-subscriptions";

  /** An operation destination whose ID is not a UUID or names no operation. */
  public static final String NOT_FOUND = "not-found";

  /** Anything unexpected. */
  public static final String INTERNAL_ERROR = "internal-error";

  private static final byte[] EMPTY = new byte[0];

  private StompErrors() {}

  /**
   * The code of a failure while a client frame was handled, looking through its causes (the
   * channels wrap what a handler throws): a {@link StompRejectedException}'s own code, {@value
   * #NOT_FOUND} for an unknown operation, {@value #FORBIDDEN_DESTINATION} for one the caller may
   * not read or a handler the caller's roles do not allow, otherwise {@value #INTERNAL_ERROR}.
   */
  public static String codeFor(Throwable failure) {
    if (failure == null) {
      return INTERNAL_ERROR;
    }
    return Causes.findCause(failure, StompRejectedException.class)
        .map(StompRejectedException::code)
        .or(() -> Causes.findCause(failure, OperationNotFoundException.class).map(f -> NOT_FOUND))
        .or(
            () ->
                Causes.findCause(failure, ForbiddenOperationException.class)
                    .map(f -> FORBIDDEN_DESTINATION))
        .or(
            () ->
                Causes.findCause(failure, AccessDeniedException.class)
                    .map(f -> FORBIDDEN_DESTINATION))
        .orElse(INTERNAL_ERROR);
  }

  /**
   * An {@code ERROR} frame with {@code code} as its {@code message} header and no body, addressed
   * to {@code sessionId} when it is sent through the client outbound channel (else answered
   * directly).
   */
  public static Message<byte[]> errorFrame(String code, String sessionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setMessage(code);
    if (sessionId != null) {
      accessor.setSessionId(sessionId);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(EMPTY, accessor.getMessageHeaders());
  }
}
