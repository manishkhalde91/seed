package com.company.seed.feature.sample.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationStatus;
import com.company.seed.feature.sample.SampleExportRequested;
import com.company.seed.feature.sample.domain.SampleRecord;
import com.company.seed.feature.sample.domain.SampleRecordNotFoundException;
import com.company.seed.feature.sample.domain.SampleRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/** DEMO ONLY. The sample use cases with a mocked repository (backend-architecture.md §5 rule 8). */
class SampleRecordServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final String ACTOR = "user:u-1";

  private final SampleRecordRepository repository = mock(SampleRecordRepository.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OperationService operations = mock(OperationService.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final SampleRecordService service =
      new SampleRecordService(
          repository,
          new IdGenerator(clock),
          clock,
          () -> ACTOR,
          mock(PlatformTransactionManager.class),
          operations,
          events);

  @Test
  void startExportStartsAnOperationAndPublishesItsEvent() {
    Operation queued =
        new Operation(
            UUID.fromString("01934b6f-0000-7000-8000-000000000001"),
            SampleRecordService.EXPORT_KIND,
            null,
            ACTOR,
            OperationStatus.QUEUED,
            1,
            null,
            null,
            null,
            "corr-1",
            NOW,
            NOW);
    when(operations.start(SampleRecordService.EXPORT_KIND, null)).thenReturn(queued);

    assertThat(service.startExport()).isSameAs(queued);

    verify(events).publishEvent(new SampleExportRequested(queued.id(), ACTOR, "corr-1"));
  }

  @Test
  void createSavesANewRecordWithAUuidV7() {
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

    SampleRecord created = service.create("Example", "x");

    assertThat(created.id().version()).isEqualTo(7);
    assertThat(created.version()).isNull();
    assertThat(created.name()).isEqualTo("Example");
    assertThat(created.description()).isEqualTo("x");
    verify(repository).save(created);
  }

  @Test
  void aNameIsStrippedOfSurroundingWhitespaceButKeepsItsCase() {
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

    assertThat(service.create("  Example\t", null).name()).isEqualTo("Example");
    assertThat(service.create("eXample", null).name()).isEqualTo("eXample");
  }

  @Test
  void aDeleteThatLostARaceToAnotherDeleteIsNotFound() {
    SampleRecord stored = stored(0);
    when(repository.findById(stored.id()))
        .thenReturn(Optional.of(stored))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));

    assertThatThrownBy(() -> service.delete(stored.id()))
        .isInstanceOf(SampleRecordNotFoundException.class);
  }

  @Test
  void aDeleteThatLostARaceToAnUpdateIsAConflict() {
    SampleRecord stored = stored(0);
    when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
    when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));

    assertThatThrownBy(() -> service.delete(stored.id()))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void aRestoreThatLostARaceToAnotherRestoreIsAnInvalidStateTransition() {
    SampleRecord restored = stored(1);
    when(repository.findById(restored.id()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(restored));
    when(repository.restore(restored.id())).thenReturn(false);

    assertThatThrownBy(() -> service.restore(restored.id()))
        .extracting("errorCode", "httpStatus")
        .containsExactly("INVALID_STATE_TRANSITION", 409);
  }

  @Test
  void getAndUpdateOfAMissingRecordAreNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id)).isInstanceOf(SampleRecordNotFoundException.class);
    assertThatThrownBy(() -> service.update(id, "n", null, 0))
        .isInstanceOf(SampleRecordNotFoundException.class)
        .extracting("errorCode")
        .isEqualTo("SAMPLE_RECORD_NOT_FOUND");
    verify(repository, never()).save(any());
  }

  @Test
  void updateReplacesNameAndDescriptionWhenTheVersionMatches() {
    SampleRecord stored = stored(3);
    when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

    SampleRecord updated = service.update(stored.id(), "Renamed", null, 3);

    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.description()).isNull();
    verify(repository).save(stored);
  }

  @Test
  void updateWithAnotherVersionIsAConflictAndSavesNothing() {
    SampleRecord stored = stored(3);
    when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> service.update(stored.id(), "Renamed", null, 2))
        .extracting("errorCode", "httpStatus")
        .containsExactly("CONCURRENT_MODIFICATION", 409);
    verify(repository, never()).save(any());
  }

  @Test
  void deleteMarksTheRecordWithTheClockAndTheActor() {
    SampleRecord stored = stored(0);
    when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

    service.delete(stored.id());

    ArgumentCaptor<SampleRecord> saved = ArgumentCaptor.forClass(SampleRecord.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().isDeleted()).isTrue();
    assertThat(saved.getValue().deletedAt()).isEqualTo(NOW);
    assertThat(saved.getValue().deletedBy()).isEqualTo(ACTOR);
  }

  @Test
  void restoreOfAnActiveRecordIsAnInvalidStateTransition() {
    SampleRecord stored = stored(0);
    when(repository.findById(stored.id())).thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> service.restore(stored.id()))
        .extracting("errorCode", "httpStatus")
        .containsExactly("INVALID_STATE_TRANSITION", 409);
    verify(repository, never()).restore(any());
  }

  @Test
  void restoreOfAnUnknownRecordIsNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());
    when(repository.restore(id)).thenReturn(false);

    assertThatThrownBy(() -> service.restore(id)).isInstanceOf(SampleRecordNotFoundException.class);
  }

  @Test
  void restoreReturnsTheRecordAsStoredAfterTheRestore() {
    SampleRecord restored = stored(2);
    when(repository.findById(restored.id()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(restored));
    when(repository.restore(restored.id())).thenReturn(true);

    assertThat(service.restore(restored.id())).isSameAs(restored);
  }

  @Test
  void listPassesTheQueryThrough() {
    PageQuery query = PageQuery.of(1, 10, PageQuery.Order.asc("name"));
    PageResult<SampleRecord> page = new PageResult<>(List.of(stored(0)), 1, 10, 11);
    when(repository.findPage(query)).thenReturn(page);

    assertThat(service.list(query)).isSameAs(page);
  }

  private static SampleRecord stored(long version) {
    return SampleRecord.stored(
        UUID.randomUUID(),
        "Stored",
        "text",
        version,
        NOW.minusSeconds(60),
        "user:creator",
        NOW.minusSeconds(30),
        "user:editor",
        null,
        null);
  }
}
