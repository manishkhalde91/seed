package com.company.seed.common.operations.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/** The STOMP {@code ERROR} codes (api-standards.md §9) a failure maps to, and the frame's shape. */
class StompErrorsTest {

  private static final Principal USER = () -> "test-user";

  @Test
  void aRejectionKeepsItsCodeAlsoWhenTheChannelWrapsIt() {
    StompRejectedException rejected =
        new StompRejectedException(StompErrors.TOO_MANY_SUBSCRIPTIONS);

    assertThat(StompErrors.codeFor(rejected)).isEqualTo(StompErrors.TOO_MANY_SUBSCRIPTIONS);
    assertThat(
            StompErrors.codeFor(
                new MessageDeliveryException(frame(StompCommand.SUBSCRIBE, USER), rejected)))
        .isEqualTo(StompErrors.TOO_MANY_SUBSCRIPTIONS);
  }

  @Test
  void anUnknownOperationIsNotFoundAndAnotherCallersOrADeniedRoleIsAForbiddenDestination() {
    OperationNotFoundException unknown = new OperationNotFoundException(UUID.randomUUID());
    ForbiddenOperationException notTheirs = new ForbiddenOperationException("Access denied");

    assertThat(StompErrors.codeFor(unknown)).isEqualTo(StompErrors.NOT_FOUND);
    assertThat(
            StompErrors.codeFor(
                new MessageHandlingException(frame(StompCommand.SUBSCRIBE, USER), unknown)))
        .isEqualTo(StompErrors.NOT_FOUND);
    assertThat(StompErrors.codeFor(notTheirs)).isEqualTo(StompErrors.FORBIDDEN_DESTINATION);
    assertThat(StompErrors.codeFor(new AccessDeniedException("Access Denied")))
        .as("a handler the caller's roles do not allow")
        .isEqualTo(StompErrors.FORBIDDEN_DESTINATION);
  }

  @Test
  void anythingElseIsAnInternalError() {
    assertThat(StompErrors.codeFor(new IllegalStateException("boom")))
        .isEqualTo(StompErrors.INTERNAL_ERROR);
    assertThat(StompErrors.codeFor(null)).isEqualTo(StompErrors.INTERNAL_ERROR);
  }

  /**
   * Spring refuses these before the inbound channel: a frame before CONNECT ("Unknown session") and
   * a second CONNECT ("Session already exists").
   */
  @Test
  void aFrameBeforeConnectOrASecondConnectIsUnauthorized() {
    IllegalStateException unknownSession = new IllegalStateException("Unknown session: 1");

    assertThat(StompErrorHandler.codeOf(frame(StompCommand.SUBSCRIBE, null), unknownSession))
        .isEqualTo(StompErrors.UNAUTHORIZED);
    assertThat(StompErrorHandler.codeOf(frame(StompCommand.SEND, null), unknownSession))
        .isEqualTo(StompErrors.UNAUTHORIZED);
    assertThat(
            StompErrorHandler.codeOf(
                frame(StompCommand.CONNECT, null),
                new IllegalStateException("Session already exists")))
        .isEqualTo(StompErrors.UNAUTHORIZED);
    assertThat(StompErrorHandler.codeOf(frame(StompCommand.SUBSCRIBE, USER), unknownSession))
        .isEqualTo(StompErrors.INTERNAL_ERROR);
    assertThat(StompErrorHandler.codeOf(null, unknownSession))
        .isEqualTo(StompErrors.INTERNAL_ERROR);
  }

  @Test
  void theErrorFrameCarriesExactlyTheCodeAndNoBody() {
    Message<byte[]> error =
        new StompErrorHandler()
            .handleClientMessageProcessingError(
                frame(StompCommand.SUBSCRIBE, USER),
                new MessageDeliveryException(
                    frame(StompCommand.SUBSCRIBE, USER),
                    new StompRejectedException(StompErrors.FORBIDDEN_DESTINATION)));

    StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
    assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
    assertThat(headers.getMessage()).isEqualTo(StompErrors.FORBIDDEN_DESTINATION);
    assertThat(headers.toNativeHeaderMap()).containsOnlyKeys("message");
    assertThat(error.getPayload()).isEmpty();
  }

  @Test
  void anErrorFrameForASessionIsAddressedToIt() {
    Message<byte[]> error = StompErrors.errorFrame(StompErrors.TOKEN_EXPIRED, "session-1");

    StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
    assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
    assertThat(headers.getMessage()).isEqualTo(StompErrors.TOKEN_EXPIRED);
    assertThat(headers.getSessionId()).isEqualTo("session-1");
  }

  private static Message<byte[]> frame(StompCommand command, Principal user) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setSessionId("session-1");
    if (user != null) {
      accessor.setUser(user);
    }
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
