package com.company.seed.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

/**
 * Checks the production classes of {@code com.company.seed} against every rule in {@link
 * SeedArchitectureRules#ALL}, so a rule added there is checked here without further changes.
 */
@AnalyzeClasses(packages = "com.company.seed", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule allSeedRules = CompositeArchRule.of(SeedArchitectureRules.ALL);
}
