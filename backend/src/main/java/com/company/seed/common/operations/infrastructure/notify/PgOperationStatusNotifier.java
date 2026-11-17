package com.company.seed.common.operations.infrastructure.notify;

import com.company.seed.common.operations.application.OperationStatusPayload;
import com.company.seed.common.operations.application.port.OperationStatusNotifier;
import com.company.seed.common.operations.domain.Operation;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OperationStatusNotifier} with {@code pg_notify('operation_status', <payload>)}, sent on
 * the caller's transaction, so PostgreSQL delivers it to every listening instance only when the
 * change commits. The payload is the push of api-standards.md §9 ({@link OperationStatusPayload}),
 * which {@link PgStatusListener} forwards unchanged; PostgreSQL limits it to 8,000 bytes.
 */
@Component
class PgOperationStatusNotifier implements OperationStatusNotifier {

  /** The channel every instance listens on. */
  static final String CHANNEL = "operation_status";

  /** PostgreSQL rejects a NOTIFY payload of 8,000 bytes or more. */
  static final int MAX_PAYLOAD_BYTES = 7999;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  PgOperationStatusNotifier(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void statusChanged(Operation operation) {
    String payload = json.writeValueAsString(OperationStatusPayload.of(operation));
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "The status push of operation " + operation.id() + " is 8,000 bytes or more");
    }
    jdbc.sql("SELECT pg_notify(?, ?)").params(CHANNEL, payload).query(row -> {});
  }
}
