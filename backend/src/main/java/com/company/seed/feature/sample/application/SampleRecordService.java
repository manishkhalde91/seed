package com.company.seed.feature.sample.application;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.operations.application.OperationService;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.feature.sample.SampleExportRequested;
import com.company.seed.feature.sample.domain.SampleRecord;
import com.company.seed.feature.sample.domain.SampleRecordNotDeletedException;
import com.company.seed.feature.sample.domain.SampleRecordNotFoundException;
import com.company.seed.feature.sample.domain.SampleRecordRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DEMO ONLY. The sample use cases, one transaction each. A duplicate active name is left to the
 * database's partial unique index (409 {@code SAMPLE_RECORD_ALREADY_EXISTS} through the module's
 * constraint mapping); a stale {@code version} is rejected here and, for a race between load and
 * commit, by {@code @Version}.
 */
@Service
@Transactional
public class SampleRecordService {

  /** The operation kind of a sample export. */
  public static final String EXPORT_KIND = "SAMPLE_EXPORT";

  private final SampleRecordRepository repository;
  private final IdGenerator ids;
  private final Clock clock;
  private final CurrentActor currentActor;
  private final TransactionTemplate transaction;
  private final OperationService operations;
  private final ApplicationEventPublisher events;

  public SampleRecordService(
      SampleRecordRepository repository,
      IdGenerator ids,
      Clock clock,
      CurrentActor currentActor,
      PlatformTransactionManager transactionManager,
      OperationService operations,
      ApplicationEventPublisher events) {
    this.repository = repository;
    this.ids = ids;
    this.clock = clock;
    this.currentActor = currentActor;
    this.transaction = new TransactionTemplate(transactionManager);
    this.operations = operations;
    this.events = events;
  }

  /**
   * Starts an export: a {@code QUEUED} {@value #EXPORT_KIND} operation and the event that does the
   * work after commit ({@code SampleExportListener}), in one transaction.
   */
  public Operation startExport() {
    Operation operation = operations.start(EXPORT_KIND, null);
    events.publishEvent(
        new SampleExportRequested(
            operation.id(), operation.requestedBy(), operation.correlationId()));
    return operation;
  }

  public SampleRecord create(String name, String description) {
    return repository.save(SampleRecord.create(ids.newId(), name, description));
  }

  @Transactional(readOnly = true)
  public SampleRecord get(UUID id) {
    return load(id);
  }

  @Transactional(readOnly = true)
  public PageResult<SampleRecord> list(PageQuery query) {
    return repository.findPage(query);
  }

  /** A full replace of name and description, if {@code version} is the stored one. */
  public SampleRecord update(UUID id, String name, String description, long version) {
    SampleRecord sampleRecord = load(id);
    sampleRecord.update(name, description, version);
    return repository.save(sampleRecord);
  }

  /**
   * Soft-deletes the record in a transaction of its own, so that a lost race can be told apart
   * afterwards: when another request deleted the record first, the loser gets 404 like any later
   * delete; when another request changed it, 409.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void delete(UUID id) {
    try {
      transaction.executeWithoutResult(
          status -> {
            SampleRecord sampleRecord = load(id);
            sampleRecord.markDeleted(clock.instant(), currentActor.id());
            repository.save(sampleRecord);
          });
    } catch (OptimisticLockingFailureException ex) {
      if (transaction.execute(status -> repository.findById(id)).isEmpty()) {
        throw new SampleRecordNotFoundException(id);
      }
      throw ex;
    }
  }

  /**
   * Brings a deleted record back.
   *
   * @throws SampleRecordNotDeletedException when the record is active
   * @throws SampleRecordNotFoundException when no record has the ID
   */
  public SampleRecord restore(UUID id) {
    if (repository.findById(id).isPresent()) {
      throw new SampleRecordNotDeletedException(id);
    }
    if (!repository.restore(id)) {
      // Nothing was deleted any more: another request may have restored it meanwhile.
      if (repository.findById(id).isPresent()) {
        throw new SampleRecordNotDeletedException(id);
      }
      throw new SampleRecordNotFoundException(id);
    }
    return load(id);
  }

  private SampleRecord load(UUID id) {
    return repository.findById(id).orElseThrow(() -> new SampleRecordNotFoundException(id));
  }
}
