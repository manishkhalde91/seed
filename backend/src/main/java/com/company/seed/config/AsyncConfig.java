package com.company.seed.config;

import com.company.seed.common.actor.SystemActor;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Async work and {@code @ApplicationModuleListener} methods (realtime-and-storage.md §1.2) run on
 * Boot's bounded {@code applicationTaskExecutor} ({@code spring.task.execution.pool.*}). Its {@link
 * TaskDecorator} carries the submitting thread's context into the task, so the work logs and audits
 * as the request that caused it: the MDC ({@code correlationId}, {@code user.id}, {@code
 * operationId}), the {@link SecurityContext} and the {@link SystemActor} scope. All three are
 * cleared after the task, so nothing leaks into the next task on the pooled thread.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

  @Bean
  TaskDecorator contextPropagatingTaskDecorator() {
    return AsyncConfig::propagating;
  }

  static Runnable propagating(Runnable task) {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    SecurityContext security = SecurityContextHolder.getContext();
    String scope = SystemActor.current();
    return () -> {
      Map<String, String> previousMdc = MDC.getCopyOfContextMap();
      SecurityContext previousSecurity = SecurityContextHolder.getContext();
      try {
        if (mdc == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(mdc);
        }
        SecurityContextHolder.setContext(security);
        if (scope == null) {
          task.run();
        } else {
          SystemActor.run(scope, task);
        }
      } finally {
        if (previousMdc == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previousMdc);
        }
        SecurityContextHolder.setContext(previousSecurity);
      }
    };
  }
}
