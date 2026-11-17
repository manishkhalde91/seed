package com.company.seed.common.operations.infrastructure.ws;

import com.company.seed.common.ids.IdGenerator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The live operation status endpoint (api-standards.md §9, realtime-and-storage.md §1):
 *
 * <ul>
 *   <li>STOMP 1.2 over plain WebSocket (no SockJS) at {@value StompSessions#ENDPOINT}, on the
 *       simple in-memory broker for {@value StompSessions#TOPIC_PREFIX}, with application prefix
 *       {@value StompSessions#APPLICATION_PREFIX} and heart-beat {@code 10000,10000}.
 *       Cross-instance fan-out comes from {@code PgStatusListener}, not from a broker relay. The
 *       broker publishes to each session in order, so a client sees an operation's versions in
 *       order.
 *   <li>The handshake accepts a request without {@code Origin} (a non-browser client), a
 *       same-origin request, and the origins of {@code seed.security.cors-allowed-origins} ({@code
 *       CORS_ALLOWED_ORIGINS}); any other origin gets 403 without an upgrade.
 *   <li>Limits: an inbound message of at most {@value #MESSAGE_SIZE_LIMIT} bytes (a larger one
 *       closes the session), {@code sendTimeLimit} {@value #SEND_TIME_LIMIT_MILLIS} ms and {@code
 *       sendBufferSizeLimit} {@value #SEND_BUFFER_SIZE_LIMIT} bytes per session.
 *   <li>A session's frames are handled in the order they arrived, each in its session's logging
 *       context ({@link StompContextInterceptor}). A refused frame gets an {@code ERROR} frame
 *       (sent by the interceptor that refused it, or by {@link StompErrorHandler}).
 *   <li>The inbound and outbound channels run on pools of their own, not on the {@code
 *       applicationTaskExecutor} that runs operation work.
 * </ul>
 *
 * Authentication on {@code CONNECT} and the subscription rules are the security module's ({@code
 * security.ws}), whose configurer comes after this one ({@link #ORDER}). Only in a servlet web
 * application, so never in the {@code migrate} run.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSocketMessageBroker
@Order(OperationStatusWebSocketConfig.ORDER)
public class OperationStatusWebSocketConfig implements WebSocketMessageBrokerConfigurer {

  /**
   * The order of this configurer: after Spring Boot's (which sets the channel executors), before
   * the security module's, so the logging context is set before a frame is authorised.
   */
  public static final int ORDER = 100;

  static final int MESSAGE_SIZE_LIMIT = 16 * 1024;
  static final int SEND_TIME_LIMIT_MILLIS = 15_000;
  static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
  static final long HEARTBEAT_MILLIS = 10_000;

  /**
   * The browser origins allowed to call the API (security.md §4), read without the security module.
   */
  static final String ALLOWED_ORIGINS_PROPERTY = "seed.security.cors-allowed-origins";

  private final StompSessionRegistry sessions;
  private final IdGenerator ids;
  private final TaskScheduler heartbeatScheduler;
  private final List<String> allowedOrigins;

  OperationStatusWebSocketConfig(
      StompSessionRegistry sessions,
      IdGenerator ids,
      @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler,
      Environment environment) {
    this.sessions = sessions;
    this.ids = ids;
    this.heartbeatScheduler = heartbeatScheduler;
    this.allowedOrigins =
        Binder.get(environment)
            .bind(ALLOWED_ORIGINS_PROPERTY, Bindable.listOf(String.class))
            .orElse(List.of())
            .stream()
            .filter(origin -> origin != null && !origin.isBlank())
            .map(String::strip)
            .toList();
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint(StompSessions.ENDPOINT)
        .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    registry.setErrorHandler(new StompErrorHandler());
    // One session's frames are handled one after the other: a snapshot sent after its topic
    // subscription is answered after that subscription is registered, so no change falls between
    // them, and SUBSCRIBE/UNSUBSCRIBE reach the broker in order.
    registry.setPreserveReceiveOrder(true);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.setApplicationDestinationPrefixes(StompSessions.APPLICATION_PREFIX);
    registry.setPreservePublishOrder(true);
    registry
        .enableSimpleBroker(StompSessions.TOPIC_PREFIX)
        .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
        .setTaskScheduler(heartbeatScheduler);
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration
        .setMessageSizeLimit(MESSAGE_SIZE_LIMIT)
        .setSendTimeLimit(SEND_TIME_LIMIT_MILLIS)
        .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT)
        .addDecoratorFactory(sessions);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration
        .executor(channelExecutor("clientInboundChannel-"))
        .interceptors(new StompContextInterceptor(ids));
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.executor(channelExecutor("clientOutboundChannel-"));
  }

  /**
   * A channel's own pool, as Spring would make it: Spring Boot would run the channels on the
   * bounded {@code applicationTaskExecutor}, where long-running operation work could hold back
   * frames and pushes, and a full queue would drop them.
   */
  private static ThreadPoolTaskExecutor channelExecutor(String threadNamePrefix) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
    executor.setAllowCoreThreadTimeOut(true);
    executor.setThreadNamePrefix(threadNamePrefix);
    return executor;
  }
}
