package com.company.seed.common.operations.application;

import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.SeedException;
import com.company.seed.common.error.web.Causes;
import com.company.seed.common.operations.domain.InvalidOperationTransitionException;
import com.company.seed.common.operations.domain.Operation;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the work of an operation from its {@code @ApplicationModuleListener}
 * (realtime-and-storage.md §1.2, errors-and-logging.md §2):
 *
 * <ul>
 *   <li>as the requester ({@code SystemActor} scope from the event), with MDC {@code correlationId}
 *       and {@code operationId}, so its log lines and audit columns name the request that started
 *       it;
 *   <li>from the status it finds, because a restart or a resubmission can deliver the event again:
 *       {@code QUEUED} is marked {@code RUNNING} first, {@code RUNNING} carries on, a finished
 *       operation is left alone;
 *   <li>in a transaction of its own per attempt, retried up to {@value #MAX_RETRIES} times with
 *       exponential backoff; a {@link SeedException} is permanent and not retried;
 *   <li>after the last failure the operation is {@code FAILED} with a safe code and message, the
 *       error is logged once at ERROR (with its SQLSTATE when there is one), and the listener
 *       returns normally: the publication completes, so a permanent failure is not resubmitted
 *       forever. A result that cannot be recorded (for example a push over 8,000 bytes) fails the
 *       operation the same way;
 *   <li>when another delivery of the same event changed the status first (its conditional update
 *       won), this one stops: the operation already shows the other delivery's outcome.
 * </ul>
 *
 * A listener declares {@code @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)}:
 * each attempt and each status change opens its own transaction, so a listener transaction around
 * them would hold a second pooled connection per executor thread for nothing.
 */
@Component
public class OperationListenerSupport {

  /** The MDC key naming the operation a log line is about (errors-and-logging.md §4). */
  public static final String OPERATION_ID_MDC_KEY = "operationId";

  static final int MAX_RETRIES = 3;
  static final String FAILED_MESSAGE = "The operation failed";

  private static final Logger LOG = LoggerFactory.getLogger(OperationListenerSupport.class);

  private final OperationService operations;
  private final TransactionTemplate attempt;
  private final RetryTemplate retry;

  public OperationListenerSupport(
      OperationService operations,
      PlatformTransactionManager transactionManager,
      @Value("${seed.operations.retry-delay:1s}") Duration retryDelay) {
    this.operations = operations;
    this.attempt = new TransactionTemplate(transactionManager);
    this.attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.retry =
        new RetryTemplate(
            RetryPolicy.builder()
                .maxRetries(MAX_RETRIES)
                .delay(retryDelay)
                .multiplier(2)
                .excludes(SeedException.class)
                .build());
  }

  /**
   * Runs {@code work} for operation {@code operationId} and records its outcome; {@code work}
   * returns the small result stored on success (for example {@code {"rowCount": 3}}).
   */
  public void run(
      UUID operationId,
      String requestedBy,
      String correlationId,
      Supplier<Map<String, Object>> work) {
    Map<String, String> outerMdc = MDC.getCopyOfContextMap();
    try {
      MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, correlationId);
      MDC.put(OPERATION_ID_MDC_KEY, operationId.toString());
      SystemActor.run(requestedBy, () -> runAsRequester(operationId, work));
    } finally {
      if (outerMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(outerMdc);
      }
    }
  }

  private void runAsRequester(UUID operationId, Supplier<Map<String, Object>> work) {
    Optional<Operation> found = operations.find(operationId);
    if (found.isEmpty()) {
      LOG.warn("Operation {} no longer exists; its work is skipped", operationId);
      return;
    }
    Operation operation = found.get();
    switch (operation.status()) {
      case SUCCEEDED, FAILED -> {
        LOG.info("Operation {} is already {}; nothing to do", operationId, operation.status());
        return;
      }
      case QUEUED -> {
        int queued = operation.version();
        Change running = unlessLost(() -> operations.markRunning(operationId, queued));
        if (running.lost()) {
          return;
        }
        operation = running.operation();
      }
      case RUNNING -> LOG.info("Operation {} resumes after a restart or resubmission", operationId);
    }
    int version = operation.version();
    Outcome outcome = attemptWithRetries(work);
    if (outcome.failure() != null) {
      RetryException failure = outcome.failure();
      Throwable cause = failure.getLastException() == null ? failure : failure.getLastException();
      LOG.error(
          "Operation {} failed after {} attempt(s) (SQLSTATE {})",
          operationId,
          failure.getRetryCount() + 1,
          sqlState(cause),
          cause);
      unlessLost(
          () -> operations.fail(operationId, version, ErrorCode.OPERATION_FAILED, FAILED_MESSAGE));
      return;
    }
    Map<String, Object> result =
        outcome.result() == null ? Map.of() : new LinkedHashMap<>(outcome.result());
    Optional<RuntimeException> notRecorded = recordResult(operationId, version, result);
    if (notRecorded.isPresent()) {
      LOG.error(
          "Operation {} finished but its result could not be recorded (SQLSTATE {})",
          operationId,
          sqlState(notRecorded.get()),
          notRecorded.get());
      unlessLost(
          () -> operations.fail(operationId, version, ErrorCode.OPERATION_FAILED, FAILED_MESSAGE));
    }
  }

  /**
   * Completes the operation with {@code result}. A result that cannot be recorded is the outcome
   * (the completion rolled back, the status is unchanged); a lost race is not, it is logged.
   */
  private Optional<RuntimeException> recordResult(
      UUID operationId, int version, Map<String, Object> result) {
    try {
      unlessLost(() -> operations.complete(operationId, version, result));
      return Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.of(ex);
    }
  }

  /**
   * The result of one status change: the changed operation, or the transition another delivery won
   * first (this one then stops; the operation already shows the other's outcome).
   */
  private record Change(Operation operation, InvalidOperationTransitionException lostTo) {

    boolean lost() {
      return lostTo != null;
    }
  }

  private static Change unlessLost(Supplier<Operation> change) {
    try {
      return new Change(change.get(), null);
    } catch (InvalidOperationTransitionException ex) {
      LOG.info(
          "Another delivery changed the operation first; this one stops ({})", ex.getMessage());
      return new Change(null, ex);
    }
  }

  private static String sqlState(Throwable cause) {
    return Causes.findCause(cause, SQLException.class)
        .map(SQLException::getSQLState)
        .orElse("none");
  }

  /** The work's result, or the retry failure after the last attempt: one is always null. */
  private record Outcome(Map<String, Object> result, RetryException failure) {}

  private Outcome attemptWithRetries(Supplier<Map<String, Object>> work) {
    try {
      return new Outcome(retry.execute(() -> attempt.execute(status -> work.get())), null);
    } catch (RetryException ex) {
      return new Outcome(null, ex);
    }
  }
}
