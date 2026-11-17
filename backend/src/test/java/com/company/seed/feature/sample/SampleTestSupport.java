package com.company.seed.feature.sample;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.time.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DEMO ONLY. Base of the sample module's ITs: the running application on its own database ({@value
 * #DATABASE}, main migrations) with a stopped clock, so audit times are exact. Every subclass
 * shares one application context; the table is emptied and the clock reset before each test, so a
 * test sees only its own records.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = TestDatabase.DATABASE_PROPERTY + "=" + SampleTestSupport.DATABASE)
@Import({TestDatabase.class, TestSecurity.class, SampleTestSupport.ClockConfig.class})
public abstract class SampleTestSupport {

  public static final String DATABASE = "sample_feature";
  public static final Instant START = Instant.parse("2026-11-17T09:00:00Z");

  @Autowired protected MutableClock clock;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void emptyTableAndResetClock() {
    jdbc.update("DELETE FROM sample.sample_record");
    clock.set(START);
  }

  /** The application clock of a sample IT: stands still until a test moves it. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class ClockConfig {

    @Bean
    @Primary
    MutableClock sampleTestClock() {
      return new MutableClock(START);
    }
  }
}
