package com.company.seed.common.operations.application;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.operations.application.port.OperationStatusNotifier;
import com.company.seed.common.operations.domain.InvalidOperationTransitionException;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import com.company.seed.common.operations.domain.OperationRepository;
import com.company.seed.common.operations.domain.OperationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way an operation starts or changes status (realtime-and-storage.md §1.2).
 *
 * <ul>
 *   <li>{@link #start} inserts {@code QUEUED}, version 1, in the caller's transaction, so the
 *       operation and the event that starts its work commit together.
 *   <li>{@link #markRunning}, {@link #complete} and {@link #fail} each commit on their own ({@code
 *       REQUIRES_NEW}): one conditional update plus {@code pg_notify}, never an external call. A
 *       change that loses its conditional update is {@link InvalidOperationTransitionException}.
 * </ul>
 *
 * {@code seed_operation_total{kind,status}} counts every finished operation.
 */
@Service
@Transactional
public class OperationService {

  /** The longest {@code error_message} stored and pushed; the push payload stays under 8 KB. */
  static final int MAX_ERROR_MESSAGE_LENGTH = 500;

  static final String METRIC = "seed.operation";

  private final OperationRepository repository;
  private final OperationStatusNotifier notifier;
  private final IdGenerator ids;
  private final Clock clock;
  private final CurrentActor currentActor;
  private final MeterRegistry meters;

  public OperationService(
      OperationRepository repository,
      OperationStatusNotifier notifier,
      IdGenerator ids,
      Clock clock,
      CurrentActor currentActor,
      MeterRegistry meters) {
    this.repository = repository;
    this.notifier = notifier;
    this.ids = ids;
    this.clock = clock;
    this.currentActor = currentActor;
    this.meters = meters;
  }

  /**
   * Starts an operation of {@code kind}: {@code QUEUED}, version 1, requested by the current actor,
   * with the request's correlation ID. Not notified: the caller answers 202 with it.
   */
  public Operation start(String kind, UUID subjectId) {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("An operation needs a kind");
    }
    String requestedBy = currentActor.id();
    // Its work runs in this actor's scope: an actor no scope accepts would fail every delivery.
    SystemActor.requireValid(requestedBy);
    Instant now = now();
    String correlationId = MDC.get(ApiResponses.CORRELATION_ID_MDC_KEY);
    Operation operation =
        new Operation(
            ids.newId(),
            kind,
            subjectId,
            requestedBy,
            OperationStatus.QUEUED,
            1,
            null,
            null,
            null,
            correlationId == null ? "none" : correlationId,
            now,
            now);
    repository.insert(operation);
    return operation;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Operation markRunning(UUID id, int expectedVersion) {
    return change(id, expectedVersion, OperationStatus.RUNNING, null, null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Operation complete(UUID id, int expectedVersion, Map<String, Object> result) {
    return change(id, expectedVersion, OperationStatus.SUCCEEDED, result, null, null);
  }

  /** Fails the operation with a safe {@code code} and {@code message} (cut to 500 characters). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Operation fail(UUID id, int expectedVersion, String code, String message) {
    Objects.requireNonNull(code, "code");
    String safe =
        message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH
            ? message
            : message.substring(0, cutAt(message, MAX_ERROR_MESSAGE_LENGTH));
    return change(id, expectedVersion, OperationStatus.FAILED, null, code, safe);
  }

  @Transactional(readOnly = true)
  public Optional<Operation> find(UUID id) {
    return repository.findById(id);
  }

  /**
   * The operation, for its requester or an ADMIN (security.md §3).
   *
   * @throws OperationNotFoundException when no operation has the ID
   * @throws ForbiddenOperationException when the current actor is neither
   */
  @Transactional(readOnly = true)
  public Operation getForCurrentActor(UUID id) {
    Operation operation =
        repository.findById(id).orElseThrow(() -> new OperationNotFoundException(id));
    if (!operation.requestedBy().equals(currentActor.id()) && !currentActor.isAdmin()) {
      throw new ForbiddenOperationException("Access denied", Map.of("operationId", id));
    }
    return operation;
  }

  private Operation change(
      UUID id,
      int expectedVersion,
      OperationStatus to,
      Map<String, Object> result,
      String errorCode,
      String errorMessage) {
    Operation changed =
        repository
            .transition(
                id, expectedVersion, to, result, errorCode, errorMessage, now(), currentActor.id())
            .orElseThrow(() -> new InvalidOperationTransitionException(id, to, expectedVersion));
    notifier.statusChanged(changed);
    if (to.isFinished()) {
      meters.counter(METRIC, "kind", changed.kind(), "status", to.name()).increment();
    }
    return changed;
  }

  /** {@code length}, or one less so a surrogate pair is not split. */
  private static int cutAt(String message, int length) {
    return Character.isHighSurrogate(message.charAt(length - 1)) ? length - 1 : length;
  }

  /** {@code timestamptz} keeps microseconds. */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
