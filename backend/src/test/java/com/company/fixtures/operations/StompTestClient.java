package com.company.fixtures.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.security.TestJwt;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Test-only: a raw STOMP 1.2 client over plain WebSocket, so a test sees exactly what a client
 * sees: every frame (heart-beats skipped) with its headers, {@code ERROR} frames and the close
 * status. Frames are written by hand, so a test can also send what a well-behaved client never
 * would.
 *
 * <pre>{@code
 * try (StompTestClient client = StompTestClient.connect(port, TestJwt.user())) {
 *   client.subscribe("sub-1", "/topic/operations/" + id);
 * }
 * }</pre>
 */
public final class StompTestClient implements AutoCloseable {

  public static final Duration WAIT = Duration.ofSeconds(10);

  /** One frame received from the server. */
  public record Frame(String command, Map<String, String> headers, String body) {

    public String header(String name) {
      return headers.get(name);
    }
  }

  private final WebSocketSession session;
  private final Handler handler;

  private StompTestClient(WebSocketSession session, Handler handler) {
    this.session = session;
    this.handler = handler;
  }

  /** Opens the WebSocket of {@code /ws} without sending CONNECT. */
  public static StompTestClient open(int port) {
    return open(port, new WebSocketHttpHeaders());
  }

  /** Opens the WebSocket of {@code /ws} with these handshake headers (an {@code Origin}, say). */
  public static StompTestClient open(int port, WebSocketHttpHeaders handshake) {
    handshake.setSecWebSocketProtocol(List.of("v12.stomp"));
    Handler handler = new Handler();
    try {
      WebSocketSession session =
          new StandardWebSocketClient()
              .execute(handler, handshake, URI.create("ws://localhost:" + port + "/ws"))
              .get(WAIT.toSeconds(), TimeUnit.SECONDS);
      return new StompTestClient(session, handler);
    } catch (Exception ex) {
      throw new IllegalStateException("WebSocket handshake failed: " + ex.getMessage(), ex);
    }
  }

  /** Opens the WebSocket, sends CONNECT with {@code token} and waits for CONNECTED. */
  public static StompTestClient connect(int port, String token) {
    StompTestClient client = open(port);
    client.sendConnect(Map.of("Authorization", TestJwt.bearer(token)));
    Frame connected = client.next();
    assertThat(connected.command()).as("answer to CONNECT: %s", connected).isEqualTo("CONNECTED");
    return client;
  }

  /**
   * Waits until the broker of {@code instance} holds a subscription to {@code destination}: the
   * broker registers a SUBSCRIBE on the inbound executor, after the frame has been accepted.
   */
  public static void awaitSubscribed(ApplicationContext instance, String destination) {
    SimpleBrokerMessageHandler broker =
        instance.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setDestination(destination);
    Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    Awaitility.await()
        .atMost(WAIT)
        .until(() -> !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty());
  }

  /** CONNECT (STOMP 1.2, no heart-beats unless {@code headers} asks) with extra headers. */
  public void sendConnect(Map<String, String> headers) {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("accept-version", "1.2");
    all.put("host", "localhost");
    all.put("heart-beat", "0,0");
    all.putAll(headers);
    send("CONNECT", all, "");
  }

  public void subscribe(String id, String destination) {
    send("SUBSCRIBE", Map.of("id", id, "destination", destination), "");
  }

  public void unsubscribe(String id) {
    send("UNSUBSCRIBE", Map.of("id", id), "");
  }

  /** Sends one frame as written (header values are not escaped). */
  public void send(String command, Map<String, String> headers, String body) {
    StringBuilder frame = new StringBuilder(command).append('\n');
    headers.forEach((name, value) -> frame.append(name).append(':').append(value).append('\n'));
    frame.append('\n').append(body).append('\0');
    try {
      session.sendMessage(new TextMessage(frame.toString()));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot send " + command, ex);
    }
  }

  /** The next frame; fails when none arrives in time. */
  public Frame next() {
    Frame frame = poll(WAIT);
    assertThat(frame).as("a frame within %s", WAIT).isNotNull();
    return frame;
  }

  /** The next frame within {@code wait}, or {@code null}. */
  public Frame poll(Duration wait) {
    try {
      return handler.frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  /** The first frame matching {@code match}, skipping others; fails when none arrives in time. */
  public Frame await(Predicate<Frame> match) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      Frame frame = poll(Duration.ofMillis(200));
      if (frame != null && match.test(frame)) {
        return frame;
      }
    }
    throw new AssertionError("No matching frame within " + WAIT);
  }

  /** The next MESSAGE on {@code destination}. */
  public Frame awaitMessage(String destination) {
    return await(
        frame ->
            frame.command().equals("MESSAGE") && destination.equals(frame.header("destination")));
  }

  /** The {@code message} header of the next ERROR frame, after which the connection closed. */
  public String awaitErrorAndClose() {
    Frame error = await(frame -> frame.command().equals("ERROR"));
    awaitClosed();
    return error.header("message");
  }

  /** The close status, once the connection has closed. */
  public CloseStatus awaitClosed() {
    try {
      return handler.closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new AssertionError("The connection did not close within " + WAIT, ex);
    }
  }

  public boolean isOpen() {
    return session.isOpen() && !handler.closed.isDone();
  }

  @Override
  public void close() {
    if (session.isOpen()) {
      try {
        session.close();
      } catch (Exception alreadyGone) {
        // The server closed it first.
      }
    }
  }

  private static final class Handler extends AbstractWebSocketHandler {

    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final StompDecoder decoder = new StompDecoder();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
      decode(ByteBuffer.wrap(message.asBytes()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
      decode(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      closed.complete(status);
    }

    private void decode(ByteBuffer buffer) {
      for (Message<byte[]> message : decoder.decode(buffer)) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() == null) {
          continue;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        accessor
            .toNativeHeaderMap()
            .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.get(0)));
        frames.add(
            new Frame(
                accessor.getCommand().name(),
                headers,
                new String(message.getPayload(), StandardCharsets.UTF_8)));
      }
    }
  }
}
