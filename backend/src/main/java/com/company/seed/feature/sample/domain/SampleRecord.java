package com.company.seed.feature.sample.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DEMO ONLY. The sample aggregate: a named record with an optional description. The name is stored
 * without leading and trailing whitespace, so {@code "Example"} and {@code "Example "} are the same
 * name; case stays significant. Plain Java; the audit fields and {@code version} are filled by
 * persistence and only read here. {@code version} is {@code null} until the record is first saved.
 */
public final class SampleRecord {

  private final UUID id;
  private String name;
  private String description;
  private final Long version;
  private final Instant createdAt;
  private final String createdBy;
  private final Instant updatedAt;
  private final String updatedBy;
  private Instant deletedAt;
  private String deletedBy;

  private SampleRecord(
      UUID id,
      String name,
      String description,
      Long version,
      Instant createdAt,
      String createdBy,
      Instant updatedAt,
      String updatedBy,
      Instant deletedAt,
      String deletedBy) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = requireName(name);
    this.description = description;
    this.version = version;
    this.createdAt = createdAt;
    this.createdBy = createdBy;
    this.updatedAt = updatedAt;
    this.updatedBy = updatedBy;
    this.deletedAt = deletedAt;
    this.deletedBy = deletedBy;
  }

  /** A new, not yet saved record. */
  public static SampleRecord create(UUID id, String name, String description) {
    return new SampleRecord(id, name, description, null, null, null, null, null, null, null);
  }

  /** A record as it was stored; used by the repository implementation only. */
  public static SampleRecord stored(
      UUID id,
      String name,
      String description,
      long version,
      Instant createdAt,
      String createdBy,
      Instant updatedAt,
      String updatedBy,
      Instant deletedAt,
      String deletedBy) {
    return new SampleRecord(
        id,
        name,
        description,
        version,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        deletedAt,
        deletedBy);
  }

  /**
   * Replaces the name and description (a full replace: a {@code null} description clears it), if
   * the caller saw the current version.
   *
   * @throws SampleRecordVersionConflictException when {@code expectedVersion} is not the current
   *     version
   */
  public void update(String name, String description, long expectedVersion) {
    if (version == null || version != expectedVersion) {
      throw new SampleRecordVersionConflictException(id, expectedVersion, version);
    }
    this.name = requireName(name);
    this.description = description;
  }

  /** Soft-deletes the record. */
  public void markDeleted(Instant at, String by) {
    this.deletedAt = Objects.requireNonNull(at, "at");
    this.deletedBy = Objects.requireNonNull(by, "by");
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  /** The optimistic-lock version; {@code null} before the first save. */
  public Long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public String createdBy() {
    return createdBy;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public String updatedBy() {
    return updatedBy;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  public String deletedBy() {
    return deletedBy;
  }

  /** Leading and trailing whitespace is not part of a name; case is. */
  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A sample record needs a name");
    }
    return name.strip();
  }
}
