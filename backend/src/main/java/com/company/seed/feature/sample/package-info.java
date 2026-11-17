/**
 * DEMO ONLY — delete in real projects (README, "The sample module"). The sample feature exercises
 * the whole foundation end to end: the envelope, validation, auditing, soft delete with an
 * ADMIN-only restore, optimistic locking, role checks, paged and sorted listing, and the Repository
 * Pattern (backend-architecture.md §4.5, §5). Copy its shape for a new feature module; do not build
 * on it.
 */
@ApplicationModule(displayName = "Sample (DEMO ONLY)", allowedDependencies = "common")
package com.company.seed.feature.sample;

import org.springframework.modulith.ApplicationModule;
