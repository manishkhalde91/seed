package com.company.seed.common.auditing;

import com.company.seed.common.actor.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills the {@link AuditableEntity} columns: the time from the application {@link Clock} (so tests
 * control it) and the actor from {@link CurrentActor} (data-and-migrations.md §4).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(
    auditorAwareRef = "auditorAware",
    dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfig {

  @Bean
  AuditorAware<String> auditorAware(CurrentActor currentActor) {
    return () -> Optional.of(currentActor.id());
  }

  @Bean
  DateTimeProvider auditingDateTimeProvider(Clock clock) {
    // timestamptz keeps microseconds; a nanosecond Instant would not equal what is read back.
    return () -> Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
  }
}
