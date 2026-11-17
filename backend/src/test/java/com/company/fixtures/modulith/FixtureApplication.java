package com.company.fixtures.modulith;

import org.springframework.modulith.Modulithic;

/**
 * Fixture application configured like {@code SeedApplication}: feature modules under {@code
 * feature/}, detected through {@code additionalPackages} and the explicitly-annotated strategy from
 * {@code application.yml}.
 */
@Modulithic(additionalPackages = "com.company.fixtures.modulith.feature")
public class FixtureApplication {}
