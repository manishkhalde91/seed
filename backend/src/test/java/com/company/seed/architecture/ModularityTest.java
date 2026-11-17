package com.company.seed.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.seed.SeedApplication;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the Spring Modulith module structure (backend-architecture.md §3; SPEC CAP-2).
 *
 * <p>The detected modules must be exactly {@code config}, {@code common} and {@code security} plus
 * any modules under {@code com.company.seed.feature}. The {@code feature} package itself is never a
 * module.
 */
class ModularityTest {

  private static final Set<String> TOP_LEVEL_MODULES = Set.of("config", "common", "security");
  private static final String FEATURE_ROOT = "com.company.seed.feature.";

  private final ApplicationModules modules = ApplicationModules.of(SeedApplication.class);

  @Test
  void verifiesModuleBoundaries() {
    modules.verify();
  }

  @Test
  void detectsExactlyTheTopLevelModulesPlusFeatureModules() {
    Set<String> topLevel =
        modules.stream()
            .filter(module -> !module.getBasePackage().getName().startsWith(FEATURE_ROOT))
            .map(ApplicationModule::getIdentifier)
            .map(Object::toString)
            .collect(Collectors.toSet());

    assertThat(topLevel).isEqualTo(TOP_LEVEL_MODULES);
    assertThat(modules.stream().map(module -> module.getBasePackage().getName()))
        .doesNotContain("com.company.seed.feature");
  }

  @Test
  void commonIsOpenAndShared() {
    assertThat(modules.getModuleByName("common"))
        .hasValueSatisfying(common -> assertThat(common.isOpen()).isTrue());
    assertThat(modules.getSharedModules().stream().map(ApplicationModule::getIdentifier))
        .map(Object::toString)
        .containsExactlyInAnyOrder("common", "security");
  }
}
