package com.company.seed.security.ws;

import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.infrastructure.ws.OperationStatusWebSocketConfig;
import com.company.seed.common.operations.infrastructure.ws.StompSessionRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The security of the STOMP endpoint (security.md §3-§4), on the client inbound channel after the
 * logging context ({@link OperationStatusWebSocketConfig}):
 *
 * <ol>
 *   <li>{@link SecurityContextChannelInterceptor}: while a frame is checked and while it is
 *       handled, the security context is the session's user, so {@code CurrentActor} and method
 *       security work in handlers;
 *   <li>{@link StompAuthorizationInterceptor}: {@code CONNECT} authentication, the subscription and
 *       {@code SEND} rules, and the subscription limit.
 * </ol>
 *
 * Not {@code @EnableWebSocketSecurity}: it requires a CSRF token on {@code CONNECT}, which does not
 * fit bearer tokens (a bearer-token client has no CSRF exposure).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(OperationStatusWebSocketConfig.ORDER + 1)
class StompSecurityConfig implements WebSocketMessageBrokerConfigurer {

  private final JwtDecoder jwtDecoder;
  private final JwtAuthenticationConverter authenticationConverter;
  private final OperationService operations;
  private final StompSessionRegistry sessions;
  private final Clock clock;

  StompSecurityConfig(
      JwtDecoder jwtDecoder,
      JwtAuthenticationConverter authenticationConverter,
      OperationService operations,
      StompSessionRegistry sessions,
      Clock clock) {
    this.jwtDecoder = jwtDecoder;
    this.authenticationConverter = authenticationConverter;
    this.operations = operations;
    this.sessions = sessions;
    this.clock = clock;
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
        new SecurityContextChannelInterceptor(),
        new StompAuthorizationInterceptor(
            jwtDecoder, authenticationConverter, operations, sessions, clock));
  }
}
