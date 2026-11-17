package com.company.seed.common.jobs;

import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.error.web.Causes;
import com.company.seed.common.ids.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs the body of a {@code @Scheduled} housekeeping job (realtime-and-storage.md §3):
 *
 * <ol>
 *   <li>takes the PostgreSQL advisory lock {@code hashtext('job:' || name)} on one connection held
 *       for the run, and skips the run when another instance holds it;
 *   <li>opens the actor scope {@code system:job:<name>} and the correlation ID {@code
 *       job:<name>:<uuidv7>} (longer than the 64-character format of client-supplied IDs, which
 *       applies to those only);
 *   <li>logs start and end at INFO and records {@code seed_job_duration_seconds{job,status}}.
 * </ol>
 *
 * A failure is logged once at ERROR (with its SQLSTATE when there is one) and returned as {@link
 * Outcome#FAILED}: it never leaves the scheduler thread. The lock is always released, also when the
 * body throws an {@link Error}.
 *
 * <pre>{@code
 * @Scheduled(cron = "${seed.jobs.operation-cleanup-cron}")
 * public void run() {
 *   jobs.run("operation-cleanup", () -> repository.deleteFinishedCreatedBefore(cutoff()));
 * }
 * }</pre>
 */
@Component
public class ScheduledJob {

  /** The actor-scope prefix of every job: {@code system:job:<name>}. */
  public static final String ACTOR_PREFIX = "system:job:";

  static final String METRIC = "seed.job.duration";

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledJob.class);
  private static final String CORRELATION_ID = ApiResponses.CORRELATION_ID_MDC_KEY;

  /** How a run ended: the job ran and finished or failed, or another instance holds its lock. */
  public enum Outcome {
    SUCCEEDED,
    FAILED,
    SKIPPED
  }

  private final DataSource dataSource;
  private final MeterRegistry meters;
  private final IdGenerator ids;

  public ScheduledJob(DataSource dataSource, MeterRegistry meters, IdGenerator ids) {
    this.dataSource = dataSource;
    this.meters = meters;
    this.ids = ids;
  }

  /**
   * Runs {@code body} as job {@code name}, unless another instance is running it now.
   *
   * @return how the run ended; a failure has already been logged
   */
  public Outcome run(String name, Runnable body) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(body, "body");
    String outerCorrelationId = MDC.get(CORRELATION_ID);
    MDC.put(CORRELATION_ID, "job:" + name + ":" + ids.newId());
    Timer.Sample sample = Timer.start(meters);
    Outcome outcome;
    try (Connection lockConnection = dataSource.getConnection()) {
      if (!tryLock(lockConnection, name)) {
        LOG.debug("Job {} skipped: another instance holds its lock", name);
        return Outcome.SKIPPED;
      }
      try {
        outcome = SystemActor.call(ACTOR_PREFIX + name, () -> attempt(name, body));
      } finally {
        unlock(lockConnection, name);
      }
    } catch (SQLException ex) {
      return lockFailed(sample, name, ex);
    } finally {
      restore(outerCorrelationId);
    }
    return recorded(sample, name, outcome);
  }

  /**
   * No connection, or a lock statement failed: logged, and recorded as a failed run, so a database
   * outage shows in the metric.
   */
  private Outcome lockFailed(Timer.Sample sample, String name, SQLException failure) {
    LOG.error(
        "Job {} could not take or release its lock (SQLSTATE {})",
        name,
        failure.getSQLState(),
        failure);
    return recorded(sample, name, Outcome.FAILED);
  }

  private Outcome recorded(Timer.Sample sample, String name, Outcome outcome) {
    sample.stop(
        meters.timer(
            METRIC, "job", name, "status", outcome == Outcome.SUCCEEDED ? "success" : "failure"));
    return outcome;
  }

  /** Runs {@code body}; its failure is logged and is the result, never thrown at the scheduler. */
  private static Outcome attempt(String name, Runnable body) {
    LOG.info("Job {} started", name);
    try {
      body.run();
    } catch (RuntimeException ex) {
      LOG.error(
          "Job {} failed (SQLSTATE {})",
          name,
          Causes.findCause(ex, SQLException.class).map(SQLException::getSQLState).orElse("none"),
          ex);
      return Outcome.FAILED;
    }
    LOG.info("Job {} finished", name);
    return Outcome.SUCCEEDED;
  }

  private static boolean tryLock(Connection connection, String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
      statement.setString(1, "job:" + name);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  /**
   * Releases the lock. The lock belongs to the database session, which outlives the pooled
   * connection's {@code close()}: when the unlock fails, the connection is aborted, so the pool
   * drops it and the session (with its lock) ends.
   */
  private static void unlock(Connection connection, String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
      statement.setString(1, "job:" + name);
      statement.execute();
    } catch (SQLException ex) {
      connection.abort(Runnable::run);
      throw ex;
    }
  }

  private static void restore(String correlationId) {
    if (correlationId == null) {
      MDC.remove(CORRELATION_ID);
    } else {
      MDC.put(CORRELATION_ID, correlationId);
    }
  }
}
