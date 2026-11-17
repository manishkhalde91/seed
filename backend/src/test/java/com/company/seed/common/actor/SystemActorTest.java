package com.company.seed.common.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class SystemActorTest {

  private final CurrentActor currentActor = new DefaultCurrentActor();

  @Test
  void outsideAnyScopeTheActorIsAnonymous() {
    assertThat(currentActor.id()).isEqualTo("anonymous");
  }

  @Test
  void aScopeIsTheCurrentActorWhileItRuns() {
    assertThat(SystemActor.call("system:test", currentActor::id)).isEqualTo("system:test");
    assertThat(currentActor.id()).isEqualTo("anonymous");
  }

  @Test
  void aScopeNamesItselfInTheMdcAndRestoresTheOuterValueOnExit() {
    List<String> seen = new ArrayList<>();
    MDC.put(CurrentActor.MDC_KEY, "u-1");
    try {
      SystemActor.run(
          "system:x",
          () -> {
            SystemActor.run("user:42", () -> seen.add(MDC.get(CurrentActor.MDC_KEY)));
            seen.add(MDC.get(CurrentActor.MDC_KEY));
          });
      seen.add(MDC.get(CurrentActor.MDC_KEY));
    } finally {
      MDC.remove(CurrentActor.MDC_KEY);
    }
    SystemActor.run("system:y", () -> seen.add(MDC.get(CurrentActor.MDC_KEY)));
    seen.add(MDC.get(CurrentActor.MDC_KEY));

    // A user scope logs the bare subject, as an authenticated request does.
    assertThat(seen).containsExactly("42", "system:x", "u-1", "system:y", null);
  }

  @Test
  void scopesNestAndTheOuterOneIsRestoredOnExit() {
    List<String> seen = new ArrayList<>();

    SystemActor.run(
        "system:outer",
        () -> {
          SystemActor.run("user:42", () -> seen.add(currentActor.id()));
          seen.add(currentActor.id());
        });
    seen.add(currentActor.id());

    assertThat(seen).containsExactly("user:42", "system:outer", "anonymous");
  }

  @Test
  void theOuterScopeIsRestoredWhenTheInnerOneThrows() {
    SystemActor.run(
        "system:outer",
        () -> {
          assertThatThrownBy(
                  () ->
                      SystemActor.run(
                          "system:inner",
                          () -> {
                            throw new IllegalStateException("boom");
                          }))
              .hasMessage("boom");
          assertThat(currentActor.id()).isEqualTo("system:outer");
        });
    assertThat(currentActor.id()).isEqualTo("anonymous");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "admin",
        "system:",
        "user:",
        "System:x",
        " system:x",
        "anonymous",
        "system:a b",
        "system:a\nb",
        "user:42\t",
        "system:a\u0000b",
        "user:a\u007fb",
        "system:a\u00a0b"
      })
  void rejectsNamesThatAreNeitherSystemNorUser(String name) {
    assertThatThrownBy(() -> SystemActor.run(name, () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(currentActor.id()).isEqualTo("anonymous");
  }

  @Test
  void rejectsANullName() {
    assertThatThrownBy(() -> SystemActor.call(null, () -> "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
