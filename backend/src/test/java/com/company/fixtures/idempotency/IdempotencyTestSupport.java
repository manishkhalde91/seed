package com.company.fixtures.idempotency;

import com.company.fixtures.db.TestDatabase;
import com.company.fixtures.logging.EcsLogLines;
import com.company.fixtures.security.TestSecurity;
import com.company.fixtures.time.MutableClock;
import com.company.fixtures.web.LenientRestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the idempotency ITs: the running application on its own throwaway database ({@code
 * seed_idempotency}, main migrations only) with {@link IdempotencyFixtures} wired in, the scheduled
 * cleanup off (tests run it by hand), a 4 KB request body limit (the global {@code
 * seed.limits.max-body-size}), a 1 KB stored-response cap, and the idempotency logger at DEBUG.
 * Every subclass shares one application context. Helpers: the clock and probe (reset before each
 * test), plain JDBC reads of {@code common.idempotency_record}, an HTTP client and the captured log
 * lines. Keys are made unique per test, because the classes share one database.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      TestDatabase.DATABASE_PROPERTY + "=seed_idempotency",
      "seed.idempotency.cleanup-cron=-",
      "seed.idempotency.max-stored-response-size="
          + IdempotencyTestSupport.MAX_STORED_RESPONSE_SIZE,
      "seed.limits.max-body-size=" + IdempotencyTestSupport.MAX_BODY_SIZE,
      "logging.level.com.company.seed.common.idempotency=DEBUG"
    })
@Import({TestDatabase.class, TestSecurity.class, IdempotencyFixtures.class})
public abstract class IdempotencyTestSupport {

  protected static final String TABLE = "common.idempotency_record";

  /** The global request body limit: larger than the stored-response cap, so the two differ. */
  protected static final String MAX_BODY_SIZE = "4KB";

  protected static final int MAX_BODY_BYTES = 4096;

  /** The largest response that is stored. */
  protected static final String MAX_STORED_RESPONSE_SIZE = "1KB";

  protected static final int MAX_STORED_RESPONSE_BYTES = 1024;

  @Autowired protected MutableClock clock;
  @Autowired protected IdempotencyProbe probe;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired private JsonMapper jsonMapper;
  @LocalServerPort private int port;

  @BeforeEach
  void resetFixtures() {
    clock.set(IdempotencyFixtures.START);
    probe.reset();
  }

  protected static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /** The stored rows for {@code key} (none or one), read with plain JDBC. */
  protected List<Map<String, Object>> rows(String key) {
    return jdbc.queryForList("SELECT * FROM " + TABLE + " WHERE idempotency_key = ?", key);
  }

  protected Map<String, Object> row(String key) {
    List<Map<String, Object>> rows = rows(key);
    if (rows.size() != 1) {
      throw new AssertionError("Expected one row for " + key + " but found " + rows.size());
    }
    return rows.getFirst();
  }

  protected long rowCount() {
    return jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
  }

  /** A client sending a {@code USER} token. */
  protected RestClient client() {
    return LenientRestClient.forPort(port);
  }

  /** A client sending {@code token}. */
  protected RestClient client(String token) {
    return LenientRestClient.forPort(port, token);
  }

  /** A client sending no token. */
  protected RestClient anonymousClient() {
    return LenientRestClient.anonymous(port);
  }

  protected Map<String, Object> parse(byte[] json) {
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  /** The messages of the captured log lines written by {@code logger} at {@code level}. */
  protected static List<String> logMessages(CapturedOutput output, Class<?> logger, String level) {
    return EcsLogLines.of(output.getOut(), logger.getName()).stream()
        .filter(line -> line.level().equals(level))
        .map(EcsLogLines.Line::message)
        .toList();
  }
}
