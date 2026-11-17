package com.company.fixtures.idempotency;

import com.company.fixtures.time.MutableClock;
import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.idempotency.IdempotencyRecordRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Wires the idempotency probe endpoints, the counting filter, a mutable clock and a repository
 * decorator that records the actor of each retention delete.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(IdempotencyProbeController.class)
public class IdempotencyFixtures {

  public static final Instant START = Instant.parse("2026-11-16T09:00:00Z");

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(START);
  }

  @Bean
  IdempotencyProbe idempotencyProbe() {
    return new IdempotencyProbe();
  }

  @Bean
  IdempotencyProbeFilter idempotencyProbeFilter(IdempotencyProbe probe) {
    return new IdempotencyProbeFilter(probe);
  }

  @Bean
  @Primary
  ActorRecordingIdempotencyRecordRepository actorRecordingIdempotencyRecordRepository(
      @Qualifier("jpaIdempotencyRecordRepository") IdempotencyRecordRepository delegate,
      CurrentActor currentActor) {
    return new ActorRecordingIdempotencyRecordRepository(delegate, currentActor);
  }
}
