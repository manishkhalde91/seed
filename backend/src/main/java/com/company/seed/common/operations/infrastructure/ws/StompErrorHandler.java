package com.company.seed.common.operations.infrastructure.ws;

import com.company.seed.common.api.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Turns a client frame that could not be handled into the {@code ERROR} frame of api-standards.md
 * §9: its {@code message} header is exactly the {@link StompErrors} code and it has no body; Spring
 * closes the connection after sending it.
 *
 * <ul>
 *   <li>A frame refused with a {@link StompRejectedException} gets its code.
 *   <li>A frame that Spring refuses before it reaches the inbound channel gets {@value
 *       StompErrors#UNAUTHORIZED} when it is a {@code CONNECT} (a second one on a session) or any
 *       other frame on a session without an authenticated user (one before {@code CONNECT}).
 *   <li>Anything else is {@value StompErrors#INTERNAL_ERROR}, logged once at ERROR with the
 *       session's correlation ID. A frame that could not even be parsed (one over the 16 KB STOMP
 *       limit, say) is logged by Spring.
 * </ul>
 *
 * <p>Frames refused by the security interceptors do not come here: they send their {@code ERROR}
 * frame themselves, because receive order is preserved and the ordered channel only logs what a
 * frame's interceptors throw.
 */
class StompErrorHandler extends StompSubProtocolErrorHandler {

  private static final Logger LOG = LoggerFactory.getLogger(StompErrorHandler.class);

  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable failure) {
    String code = codeOf(clientMessage, failure);
    if (StompErrors.INTERNAL_ERROR.equals(code) && clientMessage != null) {
      logUnexpected(clientMessage, failure);
    }
    return StompErrors.errorFrame(code, null);
  }

  /** The code of the {@code ERROR} frame answering {@code clientMessage}'s {@code failure}. */
  static String codeOf(Message<byte[]> clientMessage, Throwable failure) {
    String code = StompErrors.codeFor(failure);
    if (StompErrors.INTERNAL_ERROR.equals(code)
        && clientMessage != null
        && isRefusedBeforeTheChannel(clientMessage)) {
      return StompErrors.UNAUTHORIZED;
    }
    return code;
  }

  /** A second {@code CONNECT} on a session, or another frame before an accepted one. */
  private static boolean isRefusedBeforeTheChannel(Message<byte[]> clientMessage) {
    StompCommand command = StompHeaderAccessor.wrap(clientMessage).getCommand();
    if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
      return true;
    }
    return command != null && SimpMessageHeaderAccessor.getUser(clientMessage.getHeaders()) == null;
  }

  private static void logUnexpected(Message<byte[]> clientMessage, Throwable failure) {
    String correlationId =
        StompSessions.correlationId(
            SimpMessageHeaderAccessor.getSessionAttributes(clientMessage.getHeaders()));
    String previous = MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY);
    try {
      if (correlationId != null) {
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
      }
      LOG.error(
          "STOMP {} frame failed; the session gets ERROR {}",
          StompHeaderAccessor.wrap(clientMessage).getCommand(),
          StompErrors.INTERNAL_ERROR,
          failure);
    } finally {
      if (previous == null) {
        MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY);
      } else {
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, previous);
      }
    }
  }
}
