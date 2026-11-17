package com.company.seed.common.operations.infrastructure.ws;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.operations.application.OperationListenerSupport;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;

/**
 * The logging context of every client frame (errors-and-logging.md §4), first on the inbound
 * channel.
 *
 * <ul>
 *   <li>On {@code CONNECT}, the session's correlation ID is its {@value CorrelationIdFilter#HEADER}
 *       header when that matches the REST format ({@link CorrelationIdFilter#TOKEN}), otherwise a
 *       new UUIDv7; it is kept as a session attribute ({@link StompSessions#CORRELATION_ID}).
 *   <li>While a frame is checked (on the receiving thread) and while it is handled (on the inbound
 *       executor), MDC holds the session's {@code correlationId}, the authenticated user's subject
 *       as {@value CurrentActor#MDC_KEY} and, for an operation destination, its {@code
 *       operationId}. The entries are removed afterwards.
 * </ul>
 */
class StompContextInterceptor implements ExecutorChannelInterceptor {

  private static final String[] KEYS = {
    ApiResponses.CORRELATION_ID_MDC_KEY,
    CurrentActor.MDC_KEY,
    OperationListenerSupport.OPERATION_ID_MDC_KEY
  };

  private final IdGenerator ids;

  StompContextInterceptor(IdGenerator ids) {
    this.ids = ids;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    MessageHeaders headers = message.getHeaders();
    Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(headers);
    if (SimpMessageHeaderAccessor.getMessageType(headers) == SimpMessageType.CONNECT
        && attributes != null) {
      attributes.put(StompSessions.CORRELATION_ID, correlationIdOf(headers));
    }
    open(headers);
    return message;
  }

  @Override
  public void afterSendCompletion(
      Message<?> message, MessageChannel channel, boolean sent, Exception failure) {
    close();
  }

  @Override
  public Message<?> beforeHandle(
      Message<?> message, MessageChannel channel, MessageHandler handler) {
    open(message.getHeaders());
    return message;
  }

  @Override
  public void afterMessageHandled(
      Message<?> message, MessageChannel channel, MessageHandler handler, Exception failure) {
    close();
  }

  /**
   * A valid {@value CorrelationIdFilter#HEADER} (or {@code x-correlation-id}) of the CONNECT frame,
   * else a new UUIDv7.
   */
  private String correlationIdOf(MessageHeaders headers) {
    String supplied =
        NativeMessageHeaderAccessor.getFirstNativeHeader(CorrelationIdFilter.HEADER, headers);
    if (supplied == null) {
      // STOMP header names are case-sensitive; clients that lower-case them are accepted too.
      supplied =
          NativeMessageHeaderAccessor.getFirstNativeHeader(
              CorrelationIdFilter.HEADER.toLowerCase(Locale.ROOT), headers);
    }
    return supplied != null && CorrelationIdFilter.TOKEN.matcher(supplied).matches()
        ? supplied
        : ids.newId().toString();
  }

  private static void open(MessageHeaders headers) {
    String correlationId =
        StompSessions.correlationId(SimpMessageHeaderAccessor.getSessionAttributes(headers));
    if (correlationId != null) {
      MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
    }
    Principal user = SimpMessageHeaderAccessor.getUser(headers);
    if (user != null && user.getName() != null && !user.getName().isBlank()) {
      MDC.put(CurrentActor.MDC_KEY, user.getName());
    }
    StompSessions.operationOf(SimpMessageHeaderAccessor.getDestination(headers))
        .filter(operation -> operation.id() != null)
        .ifPresent(
            operation ->
                MDC.put(OperationListenerSupport.OPERATION_ID_MDC_KEY, operation.id().toString()));
  }

  private static void close() {
    for (String key : KEYS) {
      MDC.remove(key);
    }
  }
}
