plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "com.company"
version = "0.0.1-SNAPSHOT"
description = "Monorepo seed backend: Spring Boot foundation with Spring Modulith module boundaries."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Dependency convergence: fail when two versions of a module the BOMs do not manage meet on any
// classpath. The BOMs are enforced platforms (plain platform() trips this on the BOM's own
// resolution), so a BOM version wins even over a declared version, unlike Maven. To override one
// (e.g. a CVE fix), add resolutionStrategy.force("group:artifact:version") here with the version
// in the catalog; a strictly constraint fails to resolve (README "Verify items").
configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

dependencies {
    implementation(enforcedPlatform(libs.spring.boot.dependencies))
    implementation(enforcedPlatform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc)
    // Live operation status: STOMP over WebSocket on the simple broker (realtime-and-storage.md §1).
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // Only SecurityContextChannelInterceptor: STOMP handlers run as the session's user (security.ws).
    implementation(libs.spring.security.messaging)
    implementation(libs.spring.modulith.starter.core)
    // The event publication registry, in common.event_publication (realtime-and-storage.md §1.2).
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Compile scope: PgStatusListener reads LISTEN notifications through the driver's PGConnection.
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    // Every @SpringBootTest runs against PostgreSQL in Testcontainers (fixtures/db/TestDatabase).
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    // Test-only API used solely by the boundary-violation fixtures under
    // src/test/java/com/company/fixtures, so that the Jackson 2 rule can be shown to fail.
    // It is never on the main classpath.
    testImplementation(libs.jackson2.databind)

    testRuntimeOnly(libs.junit.platform.launcher)
}

// The split selects class files rather than using filter { }: Gradle's name filter cannot match
// the ArchUnit engine's field-based tests, so they would leak into integrationTest.
val integrationTestClasses = listOf("**/*IT.class", "**/*IT$*.class")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Tests never inherit the developer's runtime choices (.env.example exports both): an active
    // local profile would switch the log format, and the docs flag would turn on the API docs.
    environment.remove("SPRING_PROFILES_ACTIVE")
    environment.remove("SEED_API_DOCS_ENABLED")
    // ... nor its identity provider and CORS settings: tests take them from
    // src/test/resources/config/application.yml and sign their own tokens (fixtures/security).
    listOf("OIDC_ISSUER_URI", "OIDC_AUDIENCE", "OIDC_ROLES_CLAIM", "CORS_ALLOWED_ORIGINS", "SWAGGER_UI_CLIENT_ID")
        .forEach(environment::remove)
    // ... nor its request limits: tests set the ones they exercise.
    listOf("SEED_MAX_BODY_SIZE", "SEED_WRITES_PER_MINUTE", "SEED_MAX_UPLOAD_SIZE").forEach(environment::remove)
    // Audit timestamps are Instants written as UTC (data-and-migrations.md §4).
    jvmArgs("-Duser.timezone=UTC")
}

tasks.bootRun {
    jvmArgs("-Duser.timezone=UTC")
}

tasks.test {
    exclude(integrationTestClasses)
}

// OpenApiExportIT writes the generated contract here; checkOpenApi compares it with the committed
// openapi/api.yaml and updateOpenApi accepts a change (api-standards.md §7).
val generatedOpenApi = layout.buildDirectory.file("openapi/api.yaml")
val committedOpenApi = layout.projectDirectory.file("openapi/api.yaml")
val missingOpenApiMessage =
    "build/openapi/api.yaml was not generated: OpenApiExportIT must run in integrationTest " +
        "(an unfiltered run, without --tests)."

// Spring Boot configures logging only for the first application context in a JVM, so the tests
// that run the local profile (readable log lines) run in their own task (and JVM).
val localProfileTestClasses =
    listOf(
        "**/LocalReadableLogIT.class",
        "**/LocalReadableLogIT$*.class",
        "**/LocalDocsSecurityIT.class",
        "**/LocalDocsSecurityIT$*.class",
    )

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the integration tests (*IT) and writes build/openapi/api.yaml."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include(integrationTestClasses)
    exclude(localProfileTestClasses)
    failOnNoDiscoveredTests = false
    shouldRunAfter(tasks.test)
    val generatedFile = generatedOpenApi.get().asFile
    systemProperty("seed.openapi.output", generatedFile.absolutePath)
    outputs.file(generatedOpenApi)
    // A run that skips OpenApiExportIT (e.g. --tests) must not leave an old contract behind.
    doFirst { generatedFile.delete() }
}

val localProfileTest = tasks.register<Test>("localProfileTest") {
    description = "Runs the local-profile integration test (readable log lines) in its own JVM."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include(localProfileTestClasses)
    failOnNoDiscoveredTests = false
    shouldRunAfter(tasks.test)
}

val checkOpenApi = tasks.register("checkOpenApi") {
    description = "Fails when openapi/api.yaml differs from the contract generated by integrationTest."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(integrationTest)
    val generated = generatedOpenApi
    val committed = committedOpenApi
    // files(), not file(): a missing generated file gets the message below, not a validation error.
    inputs.files(generated)
    inputs.files(committed)
    doLast {
        val generatedFile = generated.get().asFile
        val committedFile = committed.asFile
        if (!generatedFile.isFile) {
            throw GradleException(missingOpenApiMessage)
        }
        if (!committedFile.isFile || !generatedFile.readBytes().contentEquals(committedFile.readBytes())) {
            throw GradleException(
                "backend/openapi/api.yaml differs from the generated contract (build/openapi/api.yaml). " +
                    "Review the API change, run ./gradlew updateOpenApi and commit the result."
            )
        }
    }
}

tasks.register("updateOpenApi") {
    description = "Copies the contract generated by integrationTest over the committed openapi/api.yaml."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(integrationTest)
    val generated = generatedOpenApi
    val committed = committedOpenApi
    doLast {
        val generatedFile = generated.get().asFile
        if (!generatedFile.isFile) {
            throw GradleException(missingOpenApiMessage)
        }
        val target = committed.asFile
        target.parentFile.mkdirs()
        generatedFile.copyTo(target, overwrite = true)
    }
}

tasks.check {
    dependsOn(integrationTest, localProfileTest, checkOpenApi)
}

tasks.jar {
    enabled = false
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
    }
}
