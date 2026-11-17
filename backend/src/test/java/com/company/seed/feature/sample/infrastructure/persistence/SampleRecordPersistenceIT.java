package com.company.seed.feature.sample.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.feature.sample.SampleTestSupport;
import com.company.seed.feature.sample.domain.SampleRecord;
import com.company.seed.feature.sample.domain.SampleRecordRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DEMO ONLY. {@link JpaSampleRecordRepository} against PostgreSQL (backend-architecture.md §5 rule
 * 8): mapping and auditing, the hidden deleted rows, paging and sorting, the native restore, the
 * partial unique index and the {@code @Version} race.
 */
class SampleRecordPersistenceIT extends SampleTestSupport {

  private static final String ACTOR = "system:sample-test";

  @Autowired private SampleRecordRepository repository;
  @Autowired private IdGenerator ids;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;
  private TransactionTemplate newTransaction;

  @BeforeEach
  void transactions() {
    transaction = new TransactionTemplate(transactionManager);
    newTransaction = new TransactionTemplate(transactionManager);
    newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void saveInsertsANewRecordWithVersionZeroAndTheAuditFields() {
    SampleRecord saved = save(SampleRecord.create(ids.newId(), "Inserted", "text"));

    assertThat(saved.version()).isZero();
    assertThat(saved.createdAt()).isEqualTo(START);
    assertThat(saved.updatedAt()).isEqualTo(START);
    assertThat(saved.createdBy()).isEqualTo(ACTOR);
    assertThat(saved.updatedBy()).isEqualTo(ACTOR);
    SampleRecord found = inTransaction(() -> repository.findById(saved.id())).orElseThrow();
    assertThat(found.name()).isEqualTo("Inserted");
    assertThat(found.description()).isEqualTo("text");
    assertThat(found.version()).isZero();
  }

  @Test
  void saveStoresChangesAndBumpsTheVersion() {
    SampleRecord saved = save(SampleRecord.create(ids.newId(), "Changed", "text"));
    clock.advance(Duration.ofMinutes(1));

    SampleRecord updated =
        inTransaction(
            () -> {
              SampleRecord loaded = repository.findById(saved.id()).orElseThrow();
              loaded.update("Changed-2", null, 0);
              return SystemActor.call("system:updater", () -> repository.save(loaded));
            });

    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.description()).isNull();
    assertThat(updated.updatedAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
    assertThat(updated.updatedBy()).isEqualTo("system:updater");
    assertThat(updated.createdBy()).isEqualTo(ACTOR);
  }

  @Test
  void deletedRecordsAreHiddenFromFindByIdAndFromPagesAndTheirTotals() {
    SampleRecord kept = save(SampleRecord.create(ids.newId(), "Kept", null));
    SampleRecord removed = save(SampleRecord.create(ids.newId(), "Removed", null));

    delete(removed.id());

    assertThat(inTransaction(() -> repository.findById(removed.id()))).isEmpty();
    PageResult<SampleRecord> page = inTransaction(() -> repository.findPage(PageQuery.of(0, 10)));
    assertThat(page.items()).extracting(SampleRecord::id).containsExactly(kept.id());
    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_by FROM sample.sample_record WHERE id = ?",
                String.class,
                removed.id()))
        .isEqualTo(ACTOR);
  }

  @Test
  void findPageSortsByTheApiFieldsAndCountsEveryActiveRecord() {
    for (String name : new String[] {"b", "c", "a", "e", "d"}) {
      save(SampleRecord.create(ids.newId(), name, null));
      clock.advance(Duration.ofSeconds(1));
    }

    PageResult<SampleRecord> byName =
        inTransaction(() -> repository.findPage(PageQuery.of(1, 2, PageQuery.Order.asc("name"))));
    PageResult<SampleRecord> newest =
        inTransaction(
            () -> repository.findPage(PageQuery.of(0, 2, PageQuery.Order.desc("createdAt"))));

    assertThat(byName.items()).extracting(SampleRecord::name).containsExactly("c", "d");
    assertThat(byName.totalElements()).isEqualTo(5);
    assertThat(byName.totalPages()).isEqualTo(3);
    assertThat(newest.items()).extracting(SampleRecord::name).containsExactly("d", "e");
  }

  @Test
  void restoreActsOnlyOnADeletedRecordAndAuditsIt() {
    SampleRecord active = save(SampleRecord.create(ids.newId(), "Active", null));
    SampleRecord removed = save(SampleRecord.create(ids.newId(), "Restored", null));
    delete(removed.id());
    long versionWhenDeleted =
        jdbc.queryForObject(
            "SELECT version FROM sample.sample_record WHERE id = ?", Long.class, removed.id());
    clock.advance(Duration.ofMinutes(1));

    assertThat(restore(active.id())).isFalse();
    assertThat(restore(UUID.randomUUID())).isFalse();
    assertThat(SystemActor.call("system:restorer", () -> restore(removed.id()))).isTrue();

    SampleRecord restored = inTransaction(() -> repository.findById(removed.id())).orElseThrow();
    assertThat(restored.isDeleted()).isFalse();
    assertThat(restored.version()).isEqualTo(versionWhenDeleted + 1);
    assertThat(restored.updatedAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
    assertThat(restored.updatedBy()).isEqualTo("system:restorer");
    assertThat(inTransaction(() -> repository.findById(active.id())).orElseThrow().version())
        .isZero();
  }

  /** The restore detaches the deleted instance it finds managed, so the next read is fresh. */
  @Test
  void aRecordDeletedAndRestoredInOneTransactionIsReadBackRestored() {
    SampleRecord saved = save(SampleRecord.create(ids.newId(), "Same transaction", null));

    SampleRecord reread =
        SystemActor.call(
            ACTOR,
            () ->
                inTransaction(
                    () -> {
                      SampleRecord loaded = repository.findById(saved.id()).orElseThrow();
                      loaded.markDeleted(clock.instant(), ACTOR);
                      repository.save(loaded);
                      assertThat(repository.restore(saved.id())).isTrue();
                      return repository.findById(saved.id()).orElseThrow();
                    }));

    assertThat(reread.isDeleted()).isFalse();
    assertThat(reread.version()).isEqualTo(2);
  }

  @Test
  void aSecondActiveRecordWithTheSameNameViolatesTheNamedUniqueIndex() {
    save(SampleRecord.create(ids.newId(), "Unique", null));

    assertThat(constraintOf(() -> save(SampleRecord.create(ids.newId(), "Unique", null))))
        .isEqualTo("uq_sample_record__name");
  }

  @Test
  void restoringARecordWhoseNameIsTakenViolatesTheUniqueIndex() {
    SampleRecord removed = save(SampleRecord.create(ids.newId(), "Reused", null));
    delete(removed.id());
    save(SampleRecord.create(ids.newId(), "Reused", null));

    assertThat(constraintOf(() -> restore(removed.id()))).isEqualTo("uq_sample_record__name");
  }

  /** A write committed between this transaction's load and its save fails the stale save. */
  @Test
  void aSaveRacingAnotherCommittedSaveFailsWithAnOptimisticLock() {
    SampleRecord saved = save(SampleRecord.create(ids.newId(), "Raced", null));

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      SampleRecord stale = repository.findById(saved.id()).orElseThrow();
                      newTransaction.executeWithoutResult(
                          status -> {
                            SampleRecord winner = repository.findById(saved.id()).orElseThrow();
                            winner.update("Raced-winner", null, 0);
                            repository.save(winner);
                          });
                      stale.update("Raced-loser", null, 0);
                      return repository.save(stale);
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(inTransaction(() -> repository.findById(saved.id())).orElseThrow().name())
        .isEqualTo("Raced-winner");
  }

  private SampleRecord save(SampleRecord sampleRecord) {
    return SystemActor.call(ACTOR, () -> inTransaction(() -> repository.save(sampleRecord)));
  }

  private void delete(UUID id) {
    SystemActor.run(
        ACTOR,
        () ->
            transaction.executeWithoutResult(
                status -> {
                  SampleRecord loaded = repository.findById(id).orElseThrow();
                  loaded.markDeleted(clock.instant(), ACTOR);
                  repository.save(loaded);
                }));
  }

  private boolean restore(UUID id) {
    return inTransaction(() -> repository.restore(id));
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  /**
   * The constraint named by the {@code DataIntegrityViolationException} that {@code work} throws.
   */
  private static String constraintOf(Runnable work) {
    try {
      work.run();
    } catch (DataIntegrityViolationException ex) {
      Throwable cause = ex;
      while (cause != null && !(cause instanceof ConstraintViolationException)) {
        cause = cause.getCause();
      }
      return cause == null ? null : ((ConstraintViolationException) cause).getConstraintName();
    }
    throw new AssertionError("No DataIntegrityViolationException was thrown");
  }
}
