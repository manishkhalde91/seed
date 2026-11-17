package com.company.seed.common.idempotency.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mapping of {@code common.idempotency_record}, used for reads only: every write is a conditional
 * statement in {@link JpaIdempotencyRecordRepository}. An internal table, so no auditing base
 * class. The unique {@code (caller, idempotency_key)} pair is the JPA identifier: the {@code
 * bigint} identity primary key is never read, so it is not mapped.
 */
@Entity
@Table(schema = "common", name = "idempotency_record")
@IdClass(IdempotencyRecordJpaEntity.Key.class)
class IdempotencyRecordJpaEntity {

  @Id
  @Column(nullable = false, updatable = false)
  private String caller;

  @Id
  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  @Column(nullable = false)
  private String requestHash;

  @Column(nullable = false)
  private UUID claimToken;

  @Column(nullable = false)
  private String status;

  private Integer responseStatus;

  private String responseContentType;

  private String responseLocation;

  private byte[] responseBody;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected IdempotencyRecordJpaEntity() {}

  /** The identifier: a record is unique per caller and key. */
  static final class Key implements Serializable {

    private String caller;
    private String idempotencyKey;

    Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key that
          && Objects.equals(caller, that.caller)
          && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(caller, idempotencyKey);
    }
  }

  String getCaller() {
    return caller;
  }

  String getIdempotencyKey() {
    return idempotencyKey;
  }

  String getRequestHash() {
    return requestHash;
  }

  UUID getClaimToken() {
    return claimToken;
  }

  String getStatus() {
    return status;
  }

  Integer getResponseStatus() {
    return responseStatus;
  }

  String getResponseContentType() {
    return responseContentType;
  }

  String getResponseLocation() {
    return responseLocation;
  }

  byte[] getResponseBody() {
    return responseBody;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }
}
