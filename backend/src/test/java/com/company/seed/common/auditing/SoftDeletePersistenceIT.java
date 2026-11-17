package com.company.seed.common.auditing;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.fixtures.persistence.AuditedThing;
import com.company.fixtures.persistence.PersistenceFixtures;
import com.company.fixtures.persistence.PersistenceTest;
import com.company.fixtures.persistence.PersistenceTestSupport;
import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.actor.SystemActor;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Soft delete and the native restore (data-and-migrations.md §5): deleted rows are hidden from JPA
 * reads, a restore acts only on a deleted row, and the partial unique index lets a new row reuse a
 * deleted row's name.
 */
@PersistenceTest
class SoftDeletePersistenceIT extends PersistenceTestSupport {

  @Autowired private CurrentActor currentActor;
  @Autowired private PlatformTransactionManager transactions;

  @Test
  void markDeletedRecordsTheClockAndActorAndHidesTheRow() {
    AuditedThing thing = create(unique("deleted"));
    clock.advance(Duration.ofSeconds(30));

    delete(thing);

    assertThat(timestamp(thing.getId(), "deleted_at")).isEqualTo(START.plusSeconds(30));
    assertThat(row(thing.getId())).containsEntry("deleted_by", "system:test");
    assertThat(repository.findById(thing.getId())).isEmpty();
    assertThat(repository.findAll()).extracting(AuditedThing::getId).doesNotContain(thing.getId());
  }

  @Test
  void restoreBringsADeletedRowBackAndAuditsIt() {
    AuditedThing thing = create(unique("restored"));
    delete(thing);
    long versionWhenDeleted = ((Number) row(thing.getId()).get("version")).longValue();
    clock.advance(Duration.ofMinutes(1));

    boolean restored = SystemActor.call("system:restorer", () -> repository.restore(thing.getId()));

    assertThat(restored).isTrue();
    AuditedThing stored = repository.findById(thing.getId()).orElseThrow();
    assertThat(stored.isDeleted()).isFalse();
    assertThat(stored.getDeletedAt()).isNull();
    assertThat(stored.getDeletedBy()).isNull();
    assertThat(stored.getUpdatedAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
    assertThat(stored.getUpdatedBy()).isEqualTo("system:restorer");
    assertThat(stored.getVersion()).isEqualTo(versionWhenDeleted + 1);
    assertThat(stored.getCreatedAt()).isEqualTo(START);
  }

  @Test
  void restoreLeavesActiveAndMissingRowsUntouched() {
    AuditedThing active = create(unique("active"));
    Map<String, Object> before = row(active.getId());
    clock.advance(Duration.ofMinutes(1));

    assertThat(repository.restore(active.getId())).isFalse();
    assertThat(repository.restore(UUID.randomUUID())).isFalse();

    assertThat(row(active.getId())).isEqualTo(before);
  }

  @Test
  void aNewRowMayReuseADeletedRowsNameButTheOriginalCannotComeBack() {
    String name = unique("reused");
    AuditedThing original = create(name);
    delete(original);

    AuditedThing replacement = create(name);

    assertThat(repository.findById(replacement.getId())).isPresent();
    ResponseEntity<Map<String, Object>> response =
        client()
            .post()
            .uri("/probe-db/things/{id}/restore", original.getId())
            .retrieve()
            .toEntity(new ParameterizedTypeReference<>() {});
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsEntry("errorCode", PersistenceFixtures.ALREADY_EXISTS)
        .containsEntry("message", "Conflicts with existing data");
    assertThat(row(original.getId()).get("deleted_at")).isNotNull();
  }

  @Test
  void aRestoreInsideATransactionLeavesNoStaleInstanceBehind() {
    AuditedThing created = create(unique("same-transaction"));

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              AuditedThing loaded = repository.findById(created.getId()).orElseThrow();
              loaded.markDeleted(clock.instant(), currentActor.id());
              repository.save(loaded);
              assertThat(repository.restore(created.getId())).isTrue();
              AuditedThing again = repository.findById(created.getId()).orElseThrow();
              again.rename(again.getName() + "-again");
              repository.saveAndFlush(again);
            });

    AuditedThing stored = repository.findById(created.getId()).orElseThrow();
    assertThat(stored.getName()).endsWith("-again");
    assertThat(stored.isDeleted()).isFalse();
    assertThat(stored.getVersion()).isEqualTo(3);
  }

  /** The application-service deletion of data-and-migrations.md §5, in a system scope. */
  private void delete(AuditedThing thing) {
    SystemActor.run(
        "system:test",
        () -> {
          AuditedThing loaded = repository.findById(thing.getId()).orElseThrow();
          loaded.markDeleted(clock.instant(), currentActor.id());
          repository.saveAndFlush(loaded);
        });
  }
}
