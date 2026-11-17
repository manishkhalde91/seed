package com.company.seed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.operations.OperationsTestSupport;
import com.company.fixtures.operations.ScheduledThreadProbe;
import java.util.concurrent.Executor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The STOMP broker brings executors and a scheduler of its own; the application's stay as they
 * were: async work and module listeners on the bounded {@code applicationTaskExecutor}, {@code
 * Scheduled} jobs on {@code taskScheduler}, and the STOMP channels on pools of their own.
 */
class TaskExecutorsIT extends OperationsTestSupport {

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private Executor applicationTaskExecutor;

  @Autowired
  @Qualifier("clientInboundChannelExecutor")
  private Executor clientInboundChannelExecutor;

  @Autowired
  @Qualifier("clientOutboundChannelExecutor")
  private Executor clientOutboundChannelExecutor;

  @Autowired
  @Qualifier("taskScheduler")
  private ThreadPoolTaskScheduler taskScheduler;

  @Autowired
  @Qualifier("messageBrokerTaskScheduler")
  private TaskScheduler messageBrokerTaskScheduler;

  @Autowired private ScheduledThreadProbe scheduledThreadProbe;

  @Test
  void asyncWorkRunsOnTheBoundedApplicationTaskExecutor() {
    assertThat(applicationTaskExecutor)
        .isInstanceOfSatisfying(
            ThreadPoolTaskExecutor.class,
            executor -> assertThat(executor.getMaxPoolSize()).isEqualTo(8));
  }

  @Test
  void scheduledJobsRunOnTheTaskSchedulerNotTheBrokers() {
    String thread =
        Awaitility.await().atMost(WAIT).until(scheduledThreadProbe::lastThread, t -> t != null);

    assertThat(thread).startsWith(taskScheduler.getThreadNamePrefix());
    assertThat(messageBrokerTaskScheduler)
        .isInstanceOfSatisfying(
            ThreadPoolTaskScheduler.class,
            broker -> assertThat(thread).doesNotStartWith(broker.getThreadNamePrefix()));
  }

  @Test
  void theStompChannelsHavePoolsOfTheirOwn() {
    assertThat(clientInboundChannelExecutor)
        .isNotSameAs(applicationTaskExecutor)
        .isNotSameAs(clientOutboundChannelExecutor);
    assertThat(clientOutboundChannelExecutor).isNotSameAs(applicationTaskExecutor);
  }
}
