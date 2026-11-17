package com.company.seed.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Turns on {@code @Scheduled} jobs (realtime-and-storage.md), except in the one-off {@code migrate}
 * run, which must only migrate and exit: there, no scheduler is created and no job runs.
 *
 * <p>The jobs run on their own {@code taskScheduler}, built like Spring Boot's ({@code
 * spring.task.scheduling.*}). Boot would back off from creating it because the STOMP broker
 * declares a scheduler of its own ({@code messageBrokerTaskScheduler}, for heart-beats), and the
 * jobs would then share the broker's threads.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!migrate")
class SchedulingConfig {

  /**
   * Boot's builder ({@code spring.task.scheduling.*}); its defaults where Boot is not configured.
   */
  @Bean
  ThreadPoolTaskScheduler taskScheduler(ObjectProvider<ThreadPoolTaskSchedulerBuilder> builder) {
    return builder.getIfAvailable(ThreadPoolTaskSchedulerBuilder::new).build();
  }
}
