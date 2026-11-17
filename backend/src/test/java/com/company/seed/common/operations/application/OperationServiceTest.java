package com.company.seed.common.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.error.ForbiddenOperationException;
import com.company.seed.common.ids.IdGenerator;
import com.company.seed.common.operations.application.port.OperationStatusNotifier;
import com.company.seed.common.operations.domain.Operation;
import com.company.seed.common.operations.domain.OperationNotFoundException;
import com.company.seed.common.operations.domain.OperationRepository;
import com.company.seed.common.operations.domain.OperationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code OperationService} with a mocked repository: who may read, what is stored on failure. */
class OperationServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final UUID ID = UUID.fromString("01934b6f-0000-7000-8000-0000000000aa");

  private final OperationRepository repository = mock(OperationRepository.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private OperationService serviceAs(String actor, boolean admin) {
    CurrentActor currentActor =
        new CurrentActor() {
          @Override
          public String id() {
            return actor;
          }

          @Override
          public boolean isAdmin() {
            return admin;
          }
        };
    return new OperationService(
        repository,
        mock(OperationStatusNotifier.class),
        new IdGenerator(clock),
        clock,
        currentActor,
        new SimpleMeterRegistry());
  }

  @Test
  void theRequesterAndAnAdminReadAnOperationAndAnotherUserIsForbidden() {
    Operation stored = operation("user:owner");
    when(repository.findById(ID)).thenReturn(Optional.of(stored));

    assertThat(serviceAs("user:owner", false).getForCurrentActor(ID)).isSameAs(stored);
    assertThat(serviceAs("user:admin", true).getForCurrentActor(ID)).isSameAs(stored);
    assertThatThrownBy(() -> serviceAs("user:other", false).getForCurrentActor(ID))
        .isInstanceOf(ForbiddenOperationException.class);
  }

  @Test
  void anUnknownOperationIsNotFound() {
    when(repository.findById(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> serviceAs("user:admin", true).getForCurrentActor(ID))
        .isInstanceOf(OperationNotFoundException.class);
  }

  @Test
  void anActorNoScopeAcceptsCannotStartAnOperation() {
    assertThatThrownBy(() -> serviceAs("user:has space", false).start("PROBE", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> serviceAs(CurrentActor.ANONYMOUS, false).start("PROBE", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).insert(any());
  }

  @Test
  void aLongFailureMessageIsCutWithoutSplittingACharacter() {
    when(repository.transition(
            eq(ID),
            anyInt(),
            eq(OperationStatus.FAILED),
            any(),
            anyString(),
            anyString(),
            any(),
            anyString()))
        .thenReturn(Optional.of(operation("user:owner")));
    // A surrogate pair (one emoji) straddles the 500-character limit.
    String message = "x".repeat(499) + "😀" + "tail";

    serviceAs("user:owner", false).fail(ID, 2, "OPERATION_FAILED", message);

    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(repository)
        .transition(
            eq(ID),
            eq(2),
            eq(OperationStatus.FAILED),
            any(),
            eq("OPERATION_FAILED"),
            stored.capture(),
            eq(NOW),
            eq("user:owner"));
    assertThat(stored.getValue()).isEqualTo("x".repeat(499));
  }

  private static Operation operation(String requestedBy) {
    return new Operation(
        ID,
        "PROBE",
        null,
        requestedBy,
        OperationStatus.FAILED,
        3,
        null,
        "OPERATION_FAILED",
        "The operation failed",
        "corr",
        NOW,
        NOW);
  }
}
