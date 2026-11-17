package com.company.fixtures.persistence;

import com.company.fixtures.time.MutableClock;
import com.company.fixtures.web.LenientRestClient;
import com.company.seed.common.ids.IdGenerator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Shared helpers of the {@link PersistenceTest} classes: the fixture repository, the clock (reset
 * to {@link PersistenceFixtures#START} before each test), plain JDBC reads of the fixture table
 * (JPA cannot see a deleted row) and an HTTP client for the probe endpoints. Names are made unique
 * per test, because the classes share one database.
 */
public abstract class PersistenceTestSupport {

  protected static final Instant START = PersistenceFixtures.START;

  @Autowired protected AuditedThingRepository repository;
  @Autowired protected MutableClock clock;
  @Autowired protected IdGenerator ids;
  @Autowired protected JdbcTemplate jdbc;
  @LocalServerPort private int port;

  @BeforeEach
  void resetClock() {
    clock.set(START);
  }

  protected AuditedThing create(String name) {
    return repository.saveAndFlush(new AuditedThing(ids.newId(), name, 1));
  }

  protected static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }

  /** The stored row, read with plain JDBC. */
  protected Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM fixture.audited_thing WHERE id = ?", id);
  }

  /** A stored timestamp column, read with plain JDBC rather than through JPA. */
  protected Instant timestamp(UUID id, String column) {
    return jdbc.queryForObject(
            "SELECT " + column + " FROM fixture.audited_thing WHERE id = ?",
            OffsetDateTime.class,
            id)
        .toInstant();
  }

  /**
   * A client for the running application that returns every status instead of throwing; it sends a
   * {@code USER} token.
   */
  protected RestClient client() {
    return LenientRestClient.forPort(port);
  }

  /** The same, sending {@code token}. */
  protected RestClient client(String token) {
    return LenientRestClient.forPort(port, token);
  }
}
