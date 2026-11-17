/**
 * Authentication, authorization and actor resolution. Closed, shared module; depends only on {@code
 * common}.
 */
@ApplicationModule(displayName = "Security", allowedDependencies = "common")
package com.company.seed.security;

import org.springframework.modulith.ApplicationModule;
