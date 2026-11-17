package com.company.seed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Entry point of the seed backend.
 *
 * <p>Application modules are detected explicitly ({@code spring.modulith.detection-strategy =
 * explicitly-annotated}): every module root carries an {@code @ApplicationModule} {@code
 * package-info.java}. {@code config}, {@code common} and {@code security} live directly under this
 * package; feature modules live under {@code com.company.seed.feature.<module>}. The {@code
 * feature} package itself is not a module.
 */
@SpringBootApplication
@Modulithic(
    additionalPackages = "com.company.seed.feature",
    sharedModules = {"common", "security"})
public class SeedApplication {

  public static void main(String[] args) {
    SpringApplication.run(SeedApplication.class, args);
  }
}
