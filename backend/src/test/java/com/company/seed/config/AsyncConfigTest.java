package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.seed.common.actor.SystemActor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * The async {@code TaskDecorator}: a task sees the submitter's MDC, security context and actor
 * scope, and the pooled thread is left clean afterwards.
 */
class AsyncConfigTest {

  private final ExecutorService pool = Executors.newSingleThreadExecutor();

  @AfterEach
  void cleanUp() {
    pool.shutdownNow();
    MDC.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void aTaskRunsWithTheSubmittersContextAndLeavesTheThreadClean() throws Exception {
    Authentication user = new TestingAuthenticationToken("u-1", "n/a", "ROLE_USER");
    SecurityContextHolder.setContext(new SecurityContextImpl(user));
    MDC.put("correlationId", "abc");
    MDC.put("operationId", "op-1");
    AtomicReference<String> correlationId = new AtomicReference<>();
    AtomicReference<String> operationId = new AtomicReference<>();
    AtomicReference<Authentication> authentication = new AtomicReference<>();
    AtomicReference<String> scope = new AtomicReference<>();

    Runnable decorated =
        SystemActor.call(
            "system:job:probe",
            () ->
                AsyncConfig.propagating(
                    () -> {
                      correlationId.set(MDC.get("correlationId"));
                      operationId.set(MDC.get("operationId"));
                      authentication.set(SecurityContextHolder.getContext().getAuthentication());
                      scope.set(SystemActor.current());
                    }));
    pool.submit(decorated).get();
    Future<String[]> after =
        pool.submit(
            () ->
                new String[] {
                  MDC.get("correlationId"),
                  SystemActor.current(),
                  String.valueOf(SecurityContextHolder.getContext().getAuthentication())
                });

    assertThat(correlationId).hasValue("abc");
    assertThat(operationId).hasValue("op-1");
    assertThat(authentication).hasValue(user);
    assertThat(scope).hasValue("system:job:probe");
    assertThat(after.get()).containsExactly(null, null, "null");
  }
}
