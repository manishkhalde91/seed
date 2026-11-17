package com.company.fixtures.archunit.preauthorize.api;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * Violation: STOMP handlers without {@code @PreAuthorize}; the guarded one and the exception
 * handler are not reported.
 */
@Controller
public class UnguardedMessageController {

  @SubscribeMapping("/things/{id}")
  public String snapshot() {
    return "unguarded";
  }

  @MessageMapping("/things")
  public void receive(String body) {}

  @SubscribeMapping("/guarded")
  @PreAuthorize("isAuthenticated()")
  public String guardedSnapshot() {
    return "guarded";
  }

  @MessageExceptionHandler
  public void failed(Exception failure) {}
}
