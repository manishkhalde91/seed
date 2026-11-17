package com.company.seed.common.operations.api;

import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.application.OperationStatusPayload;
import com.company.seed.common.operations.infrastructure.ws.StompErrors;
import com.company.seed.common.operations.infrastructure.ws.StompSessionRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * The STOMP snapshot of an operation (api-standards.md §9): {@code SUBSCRIBE /app/operations/{id}},
 * sent after subscribing to {@code /topic/operations/{id}}, is answered once with the current push
 * payload (not enveloped), built by the same code as the {@code pg_notify} payload. The
 * subscription was already checked when the frame arrived (security module, {@code security.ws}):
 * its requester or an {@code ADMIN}, as for {@code GET /api/v1/operations/{id}}.
 *
 * <p>A failure while the snapshot is built cannot reach the client as a frame by itself, so {@link
 * #failed} sends the {@code ERROR} frame and the connection closes.
 */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OperationStatusSubscriptionController {

  private static final Logger LOG =
      LoggerFactory.getLogger(OperationStatusSubscriptionController.class);

  private final OperationService operations;
  private final StompSessionRegistry sessions;

  public OperationStatusSubscriptionController(
      OperationService operations, StompSessionRegistry sessions) {
    this.operations = operations;
    this.sessions = sessions;
  }

  @SubscribeMapping("/operations/{id}")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  public Map<String, Object> snapshot(@DestinationVariable UUID id) {
    return OperationStatusPayload.of(operations.getForCurrentActor(id));
  }

  /**
   * An unknown operation is {@value StompErrors#NOT_FOUND}, one the caller may not read (or a
   * caller without the role) {@value StompErrors#FORBIDDEN_DESTINATION}, anything else {@value
   * StompErrors#INTERNAL_ERROR} (logged here, once, at ERROR); the session then gets that {@code
   * ERROR} frame and closes.
   */
  @MessageExceptionHandler
  public void failed(Exception failure, SimpMessageHeaderAccessor headers) {
    String code = StompErrors.codeFor(failure);
    if (StompErrors.INTERNAL_ERROR.equals(code)) {
      LOG.error("The operation snapshot failed; the session gets ERROR {}", code, failure);
    }
    sessions.closeWithError(headers.getSessionId(), code);
  }
}
