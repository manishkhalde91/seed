/**
 * Cross-cutting kernel: envelope, errors, logging, auditing and the other shared pieces. Open,
 * shared module: its sub-packages are visible to every other module. It never depends on {@code
 * security} or on a feature module.
 */
@ApplicationModule(
    displayName = "Common",
    type = ApplicationModule.Type.OPEN,
    allowedDependencies = {})
package com.company.seed.common;

import org.springframework.modulith.ApplicationModule;
