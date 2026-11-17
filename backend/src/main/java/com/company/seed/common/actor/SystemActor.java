package com.company.seed.common.actor;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * The thread-bound actor scope opened by work that does not run in a user's request: scheduled jobs
 * and async listeners acting on their own ({@code system:<name>}), or async work acting on a user's
 * behalf ({@code user:<sub>}, taken from the event). Scopes nest; leaving an inner scope restores
 * the outer one. While a scope is open, the MDC entry {@value CurrentActor#MDC_KEY} names the actor
 * (the bare subject of a user scope, as requests log it; the full name of a system scope), so every
 * log line of the work carries it; leaving the scope restores the outer value.
 *
 * <pre>{@code
 * SystemActor.run("system:cleanup", () -> repository.save(record));
 * }</pre>
 */
public final class SystemActor {

  static final String SYSTEM_PREFIX = "system:";
  static final String USER_PREFIX = "user:";

  private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

  private SystemActor() {}

  /** Runs {@code work} with {@code actor} as the current actor on this thread. */
  public static void run(String actor, Runnable work) {
    Objects.requireNonNull(work, "work");
    call(
        actor,
        () -> {
          work.run();
          return null;
        });
  }

  /** Calls {@code work} with {@code actor} as the current actor on this thread. */
  public static <T> T call(String actor, Supplier<T> work) {
    requireValid(actor);
    Objects.requireNonNull(work, "work");
    String outer = SCOPE.get();
    String outerLogged = MDC.get(CurrentActor.MDC_KEY);
    SCOPE.set(actor);
    MDC.put(CurrentActor.MDC_KEY, logged(actor));
    try {
      return work.get();
    } finally {
      if (outer == null) {
        SCOPE.remove();
      } else {
        SCOPE.set(outer);
      }
      if (outerLogged == null) {
        MDC.remove(CurrentActor.MDC_KEY);
      } else {
        MDC.put(CurrentActor.MDC_KEY, outerLogged);
      }
    }
  }

  /**
   * The actor of the innermost open scope on this thread, or {@code null} outside any scope. Read
   * by every {@link CurrentActor}: an open scope always wins over the request's user.
   */
  public static String current() {
    return SCOPE.get();
  }

  /**
   * The {@value CurrentActor#MDC_KEY} value of a scope: the bare subject of a {@code user:} scope,
   * as an authenticated request logs it; a {@code system:} scope keeps its full name.
   */
  private static String logged(String actor) {
    return actor.startsWith(USER_PREFIX) ? actor.substring(USER_PREFIX.length()) : actor;
  }

  /**
   * @throws IllegalArgumentException when {@code actor} cannot name a scope
   */
  public static void requireValid(String actor) {
    if (actor == null
        || !(hasName(actor, SYSTEM_PREFIX) || hasName(actor, USER_PREFIX))
        || actor.codePoints().anyMatch(SystemActor::isBlankOrControl)) {
      throw new IllegalArgumentException(
          "An actor scope is named system:<name> or user:<sub>, without whitespace or control"
              + " characters");
    }
  }

  /** The name ends up in audit columns and log lines, so it carries no spacing or controls. */
  private static boolean isBlankOrControl(int codePoint) {
    return Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint)
        || Character.isISOControl(codePoint);
  }

  private static boolean hasName(String actor, String prefix) {
    return actor.startsWith(prefix) && actor.length() > prefix.length();
  }
}
