package com.company.seed.common.operations.infrastructure.persistence;

import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationRepository;
import com.company.seed.common.operations.domain.OperationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OperationRepository} on {@code common.operation} with {@link JdbcClient}: the status
 * changes are conditional updates on {@code status_version} with {@code RETURNING}, which a JPA
 * mapping would only make harder. {@code result} is stored as {@code jsonb}.
 */
@Repository
class JdbcOperationRepository implements OperationRepository {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private static final String COLUMNS =
      "id, kind, subject_id, requested_by, status, status_version, result::text AS result,"
          + " error_code, error_message, correlation_id, created_at, updated_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcOperationRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void insert(Operation operation) {
    jdbc.sql(
            "INSERT INTO common.operation (id, kind, subject_id, requested_by, status,"
                + " status_version, result, error_code, error_message, correlation_id, created_at,"
                + " created_by, updated_at, updated_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            operation.id(),
            operation.kind(),
            operation.subjectId(),
            operation.requestedBy(),
            operation.status().name(),
            operation.version(),
            toJson(operation.result()),
            operation.errorCode(),
            operation.errorMessage(),
            operation.correlationId(),
            Timestamp.from(operation.createdAt()),
            operation.requestedBy(),
            Timestamp.from(operation.updatedAt()),
            operation.requestedBy())
        .update();
  }

  @Override
  public Optional<Operation> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM common.operation WHERE id = ?")
        .param(id)
        .query(this::toOperation)
        .optional();
  }

  @Override
  public Optional<Operation> transition(
      UUID id,
      int expectedVersion,
      OperationStatus to,
      Map<String, Object> result,
      String errorCode,
      String errorMessage,
      Instant now,
      String actor) {
    String[] allowedFrom = to.allowedFrom().stream().map(Enum::name).toArray(String[]::new);
    if (allowedFrom.length == 0) {
      return Optional.empty();
    }
    return jdbc.sql(
            "UPDATE common.operation SET status = ?, status_version = status_version + 1,"
                + " result = COALESCE(?::jsonb, result), error_code = ?, error_message = ?,"
                + " updated_at = ?, updated_by = ?"
                + " WHERE id = ? AND status_version = ? AND status = ANY (?)"
                + " RETURNING "
                + COLUMNS)
        .params(
            to.name(),
            toJson(result),
            errorCode,
            errorMessage,
            Timestamp.from(now),
            actor,
            id,
            expectedVersion,
            allowedFrom)
        .query(this::toOperation)
        .optional();
  }

  @Override
  public int deleteFinishedCreatedBefore(Instant cutoff) {
    return jdbc.sql(
            "DELETE FROM common.operation WHERE status IN ('SUCCEEDED', 'FAILED')"
                + " AND created_at < ?")
        .param(Timestamp.from(cutoff))
        .update();
  }

  private Operation toOperation(ResultSet row, int rowNumber) throws SQLException {
    String result = row.getString("result");
    return new Operation(
        row.getObject("id", UUID.class),
        row.getString("kind"),
        row.getObject("subject_id", UUID.class),
        row.getString("requested_by"),
        OperationStatus.valueOf(row.getString("status")),
        row.getInt("status_version"),
        result == null ? null : json.readValue(result, JSON_OBJECT),
        row.getString("error_code"),
        row.getString("error_message"),
        row.getString("correlation_id"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }

  private String toJson(Map<String, Object> value) {
    return value == null ? null : json.writeValueAsString(value);
  }
}
