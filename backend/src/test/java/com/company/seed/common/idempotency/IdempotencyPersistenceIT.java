package com.company.seed.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.idempotency.IdempotencyTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link IdempotencyRecordRepository} against PostgreSQL: the atomic claim, the conditional
 * take-over, completion and release bound to the claim token, retention, and the table's
 * constraints and index (data-and-migrations.md §2, §3).
 */
class IdempotencyPersistenceIT extends IdempotencyTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-16T09:00:00.123456Z");
  private static final String HASH = "a".repeat(64);
  private static final String CALLER = "user:persistence-it";

  /** An {@code expiredBefore} that treats every row as live. */
  private static final Instant LIVE = Instant.EPOCH;

  private static final StoredResponse RESPONSE =
      new StoredResponse(
          201, "application/json", "/things/1", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

  @Autowired private IdempotencyRecordRepository repository;

  @Test
  void claimInsertsAnInProgressRowOnlyOnce() {
    String key = unique("claim");
    UUID token = UUID.randomUUID();

    boolean first = repository.claim(CALLER, key, HASH, token, NOW, LIVE);
    boolean second =
        repository.claim(CALLER, key, "b".repeat(64), UUID.randomUUID(), NOW.plusSeconds(1), LIVE);

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    IdempotencyRecord record = repository.findByKey(CALLER, key, LIVE).orElseThrow();
    assertThat(record)
        .isEqualTo(
            new IdempotencyRecord(
                CALLER, key, HASH, token, IdempotencyStatus.IN_PROGRESS, null, NOW, NOW));
  }

  @Test
  void theSameKeyFromTwoCallersIsTwoIndependentRecords() {
    String key = unique("callers");
    String other = "user:someone-else";
    UUID token = UUID.randomUUID();
    UUID otherToken = UUID.randomUUID();

    assertThat(repository.claim(CALLER, key, HASH, token, NOW, LIVE)).isTrue();
    assertThat(repository.findByKey(other, key, LIVE)).isEmpty();
    assertThat(repository.claim(other, key, "b".repeat(64), otherToken, NOW, LIVE)).isTrue();
    assertThat(repository.complete(other, key, token, RESPONSE, NOW)).isFalse();
    assertThat(repository.release(other, key, token)).isFalse();
    assertThat(repository.complete(CALLER, key, token, RESPONSE, NOW)).isTrue();

    assertThat(rows(key)).hasSize(2);
    assertThat(repository.findByKey(CALLER, key, LIVE).orElseThrow().status())
        .isEqualTo(IdempotencyStatus.COMPLETED);
    assertThat(repository.findByKey(other, key, LIVE).orElseThrow())
        .extracting(IdempotencyRecord::caller, IdempotencyRecord::status)
        .containsExactly(other, IdempotencyStatus.IN_PROGRESS);
  }

  @ParameterizedTest(name = "{0} characters")
  @ValueSource(ints = {0, 301})
  void aCallerOutsideTheLengthBoundIsRejectedByTheDatabase(int length) {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + TABLE
                        + " (caller, idempotency_key, request_hash, claim_token, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'IN_PROGRESS', now(),"
                        + " now())",
                    "u".repeat(length),
                    unique("caller-length"),
                    HASH,
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_record__caller_length");
  }

  @Test
  void unknownKeyIsEmpty() {
    assertThat(repository.findByKey(CALLER, unique("missing"), LIVE)).isEmpty();
  }

  @Test
  void completeStoresTheResponseForTheClaimHolderOnly() {
    String key = unique("complete");
    UUID token = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, token, NOW, LIVE);

    boolean wrongToken = repository.complete(CALLER, key, UUID.randomUUID(), RESPONSE, NOW);
    boolean completed = repository.complete(CALLER, key, token, RESPONSE, NOW.plusSeconds(2));
    boolean again = repository.complete(CALLER, key, token, RESPONSE, NOW.plusSeconds(3));

    assertThat(wrongToken).isFalse();
    assertThat(completed).isTrue();
    assertThat(again).isFalse();
    IdempotencyRecord record = repository.findByKey(CALLER, key, LIVE).orElseThrow();
    assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
    assertThat(record.response()).isEqualTo(RESPONSE);
    assertThat(record.createdAt()).isEqualTo(NOW);
    assertThat(record.updatedAt()).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  void completeKeepsMissingHeadersAndAnEmptyBody() {
    String key = unique("bare");
    UUID token = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, token, NOW, LIVE);
    StoredResponse bare = new StoredResponse(204, null, null, new byte[0]);

    repository.complete(CALLER, key, token, bare, NOW);

    assertThat(repository.findByKey(CALLER, key, LIVE).orElseThrow().response()).isEqualTo(bare);
  }

  @Test
  void releaseDeletesTheRowForTheClaimHolderOnly() {
    String key = unique("release");
    UUID token = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, token, NOW, LIVE);

    boolean wrongToken = repository.release(CALLER, key, UUID.randomUUID());
    boolean released = repository.release(CALLER, key, token);

    assertThat(wrongToken).isFalse();
    assertThat(released).isTrue();
    assertThat(rows(key)).isEmpty();
    assertThat(repository.claim(CALLER, key, HASH, UUID.randomUUID(), NOW, LIVE)).isTrue();
  }

  @Test
  void completedRowIsNotReleased() {
    String key = unique("kept");
    UUID token = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, token, NOW, LIVE);
    repository.complete(CALLER, key, token, RESPONSE, NOW);

    assertThat(repository.release(CALLER, key, token)).isFalse();
    assertThat(rows(key)).hasSize(1);
  }

  @Test
  void takeOverNeedsAnOldEnoughClaimWithTheSameHashAndTheSeenToken() {
    String key = unique("takeover");
    UUID original = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, original, NOW, LIVE);
    Instant later = NOW.plus(Duration.ofMinutes(6));

    boolean tooYoung =
        repository.takeOver(
            CALLER, key, HASH, original, UUID.randomUUID(), NOW.minusNanos(1000), later);
    boolean otherHash =
        repository.takeOver(CALLER, key, "b".repeat(64), original, UUID.randomUUID(), NOW, later);
    boolean staleToken =
        repository.takeOver(CALLER, key, HASH, UUID.randomUUID(), UUID.randomUUID(), NOW, later);
    UUID winner = UUID.randomUUID();
    boolean taken = repository.takeOver(CALLER, key, HASH, original, winner, NOW, later);
    boolean racedLoser =
        repository.takeOver(CALLER, key, HASH, original, UUID.randomUUID(), NOW, later);

    assertThat(tooYoung).isFalse();
    assertThat(otherHash).isFalse();
    assertThat(staleToken).isFalse();
    assertThat(taken).isTrue();
    assertThat(racedLoser).isFalse();
    IdempotencyRecord record = repository.findByKey(CALLER, key, LIVE).orElseThrow();
    assertThat(record.claimToken()).isEqualTo(winner);
    assertThat(record.updatedAt()).isEqualTo(later);
    assertThat(record.createdAt()).isEqualTo(later);
  }

  @Test
  void theOriginalClaimChangesNothingAfterATakeOver() {
    String key = unique("superseded");
    UUID original = UUID.randomUUID();
    UUID winner = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, original, NOW, LIVE);
    repository.takeOver(CALLER, key, HASH, original, winner, NOW, NOW.plus(Duration.ofMinutes(6)));

    assertThat(repository.complete(CALLER, key, original, RESPONSE, NOW)).isFalse();
    assertThat(repository.release(CALLER, key, original)).isFalse();
    assertThat(repository.findByKey(CALLER, key, LIVE).orElseThrow().status())
        .isEqualTo(IdempotencyStatus.IN_PROGRESS);
    assertThat(repository.complete(CALLER, key, winner, RESPONSE, NOW)).isTrue();
    assertThat(repository.release(CALLER, key, original)).isFalse();
    assertThat(repository.findByKey(CALLER, key, LIVE).orElseThrow().response())
        .isEqualTo(RESPONSE);
  }

  @Test
  void completedRowIsNotTakenOver() {
    String key = unique("done");
    UUID token = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, token, NOW, LIVE);
    repository.complete(CALLER, key, token, RESPONSE, NOW);

    assertThat(
            repository.takeOver(
                CALLER, key, HASH, token, UUID.randomUUID(), NOW, NOW.plusSeconds(600)))
        .isFalse();
  }

  @Test
  void claimReplacesARowPastRetention() {
    String key = unique("expired");
    UUID stale = UUID.randomUUID();
    repository.claim(CALLER, key, HASH, stale, NOW, LIVE);
    repository.complete(CALLER, key, stale, RESPONSE, NOW);
    Instant later = NOW.plus(Duration.ofHours(25));
    Instant expiredBefore = later.minus(Duration.ofHours(24));
    UUID fresh = UUID.randomUUID();

    boolean claimed = repository.claim(CALLER, key, "b".repeat(64), fresh, later, expiredBefore);

    assertThat(claimed).isTrue();
    assertThat(repository.findByKey(CALLER, key, expiredBefore).orElseThrow())
        .isEqualTo(
            new IdempotencyRecord(
                CALLER,
                key,
                "b".repeat(64),
                fresh,
                IdempotencyStatus.IN_PROGRESS,
                null,
                later,
                later));
  }

  @Test
  void claimKeepsARowWithinRetention() {
    String key = unique("live");
    repository.claim(CALLER, key, HASH, UUID.randomUUID(), NOW, LIVE);

    assertThat(repository.claim(CALLER, key, HASH, UUID.randomUUID(), NOW.plusSeconds(1), NOW))
        .isFalse();
    assertThat(rows(key)).hasSize(1);
  }

  @Test
  void findByKeyIgnoresARowPastRetention() {
    String key = unique("stale");
    repository.claim(CALLER, key, HASH, UUID.randomUUID(), NOW, LIVE);

    assertThat(repository.findByKey(CALLER, key, NOW.plusNanos(1000))).isEmpty();
    assertThat(repository.findByKey(CALLER, key, NOW)).isPresent();
  }

  @Test
  void deleteCreatedBeforeRemovesOnlyOlderRows() {
    Instant cutoff = Instant.parse("2001-01-01T00:00:00Z");
    String older = unique("older");
    String atCutoff = unique("at-cutoff");
    repository.claim(CALLER, older, HASH, UUID.randomUUID(), cutoff.minusNanos(1000), LIVE);
    repository.claim(CALLER, atCutoff, HASH, UUID.randomUUID(), cutoff, LIVE);

    int deleted = repository.deleteCreatedBefore(cutoff);

    assertThat(deleted).isEqualTo(1);
    assertThat(rows(older)).isEmpty();
    assertThat(rows(atCutoff)).hasSize(1);
  }

  @Test
  void timesAreStoredToTheMicrosecond() {
    String key = unique("micros");
    repository.claim(CALLER, key, HASH, UUID.randomUUID(), NOW.plusNanos(789), LIVE);

    assertThat(
            jdbc.queryForObject(
                    "SELECT created_at FROM " + TABLE + " WHERE idempotency_key = ?",
                    OffsetDateTime.class,
                    key)
                .toInstant())
        .isEqualTo(NOW);
  }

  @Test
  void statusIsCheckedByTheDatabase() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + TABLE
                        + " (caller, idempotency_key, request_hash, claim_token, status,"
                        + " created_at, updated_at) VALUES ('"
                        + CALLER
                        + "', ?, ?, ?, 'UNKNOWN', now(), now())",
                    unique("bad-status"),
                    HASH,
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_record__status");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "ck_idempotency_record__completed_has_response, k1, "
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, COMPLETED",
    "ck_idempotency_record__key_length, "
        + "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk, "
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, IN_PROGRESS",
    "ck_idempotency_record__request_hash_length, k2, short, IN_PROGRESS"
  })
  void rowShapeIsCheckedByTheDatabase(
      String constraint, String key, String requestHash, String status) {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + TABLE
                        + " (caller, idempotency_key, request_hash, claim_token, status,"
                        + " created_at, updated_at) VALUES ('"
                        + CALLER
                        + "', ?, ?, ?, ?, now(), now())",
                    key,
                    requestHash,
                    UUID.randomUUID(),
                    status))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void tableHasTheNamedUniqueConstraintAndCreatedAtIndex() {
    List<String> constraints =
        jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = '" + TABLE + "'::regclass",
            String.class);
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes"
                + " WHERE schemaname = 'common' AND tablename = 'idempotency_record'",
            String.class);

    assertThat(constraints)
        .contains(
            "idempotency_record_pkey",
            "uq_idempotency_record__caller_idempotency_key",
            "ck_idempotency_record__caller_length",
            "ck_idempotency_record__status",
            "ck_idempotency_record__completed_has_response",
            "ck_idempotency_record__key_length",
            "ck_idempotency_record__request_hash_length");
    assertThat(indexes).contains("ix_idempotency_record__created_at");
  }
}
