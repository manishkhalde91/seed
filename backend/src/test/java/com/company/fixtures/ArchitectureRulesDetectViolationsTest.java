package com.company.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.fixtures.modulith.FixtureApplication;
import com.company.seed.architecture.SeedArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Proves that every boundary rule bites (SPEC CAP-2).
 *
 * <p>The fixtures live outside {@code com.company.seed}, so the production checks ({@code
 * ArchitectureTest}, {@code ModularityTest}) never see them and the build stays green. Each case
 * imports only one fixture package and asserts that the rule reports a violation on it.
 */
class ArchitectureRulesDetectViolationsTest {

  private static final String FIXTURES = "com.company.fixtures.archunit.";
  private static final ImportOption INCLUDE_TEST_CLASSES = location -> true;

  static Stream<Arguments> violations() {
    return Stream.of(
        Arguments.of(SeedArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE, "domainspring"),
        Arguments.of(SeedArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE, "domainjpa"),
        Arguments.of(SeedArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE, "domaininfra"),
        Arguments.of(SeedArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE, "domainapi"),
        Arguments.of(SeedArchitectureRules.APPLICATION_IS_ADAPTER_FREE, "appinfra"),
        Arguments.of(SeedArchitectureRules.APPLICATION_IS_ADAPTER_FREE, "appapi"),
        Arguments.of(SeedArchitectureRules.APPLICATION_IS_ADAPTER_FREE, "appjpa"),
        Arguments.of(SeedArchitectureRules.APPLICATION_IS_ADAPTER_FREE, "appweb"),
        Arguments.of(SeedArchitectureRules.SPRING_DATA_ONLY_IN_PERSISTENCE_ADAPTERS, "springdata"),
        Arguments.of(SeedArchitectureRules.API_USES_NO_REPOSITORY, "apirepo"),
        Arguments.of(SeedArchitectureRules.FEATURE_REPOSITORY_INTERFACES_IN_DOMAIN, "repolocation"),
        Arguments.of(SeedArchitectureRules.NO_FIELD_INJECTION, "fieldinjection"),
        Arguments.of(SeedArchitectureRules.NO_JACKSON_2_DATABIND, "jackson"),
        Arguments.of(SeedArchitectureRules.REST_CONTROLLERS_RETURN_ENVELOPE, "envelope"),
        Arguments.of(SeedArchitectureRules.COMMON_ERROR_IS_FRAMEWORK_FREE, "errorspring"),
        Arguments.of(SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE, "springdatadelete"),
        Arguments.of(SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE, "springdatacrud"),
        Arguments.of(SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE, "springdataremove"),
        Arguments.of(SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE, "springdataquery"),
        Arguments.of(SeedArchitectureRules.CONTROLLER_METHODS_ARE_PRE_AUTHORIZED, "preauthorize"),
        Arguments.of(SeedArchitectureRules.REQUEST_BODIES_CARRY_NO_AUDIT_FIELDS, "auditrequest"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("violations")
  void ruleReportsViolationOnFixture(ArchRule rule, String fixturePackage) {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + fixturePackage);
    assertThat(fixtures).as("fixture package %s has classes", fixturePackage).isNotEmpty();

    assertThatThrownBy(() -> rule.check(fixtures)).isInstanceOf(AssertionError.class);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("allRules")
  void ruleAcceptsThePrescribedLayout(ArchRule rule) {
    JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + "compliant");
    assertThat(compliant).isNotEmpty();

    assertThatCode(() -> rule.check(compliant)).doesNotThrowAnyException();
  }

  static Stream<ArchRule> allRules() {
    return SeedArchitectureRules.ALL.stream();
  }

  @Test
  void everyRuleHasAFixture() {
    assertThat(violations().map(arguments -> arguments.get()[0]).distinct())
        .containsExactlyInAnyOrderElementsOf(SeedArchitectureRules.ALL);
  }

  /**
   * Each kind of non-envelope handler is reported on its own, so weakening the rule's type check
   * (or its common.api package check) fails here even while another violation remains.
   */
  @Test
  void envelopeRuleNamesEachNonEnvelopeHandler() {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + "envelope");

    String report =
        String.join(
            "\n",
            SeedArchitectureRules.REST_CONTROLLERS_RETURN_ENVELOPE
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails());

    assertThat(report)
        .contains("RawController.plain()")
        .contains("RawController.entityWithoutEnvelope()")
        .contains("RawController.lookalikeEnvelope()")
        .contains("RawController.packagePrivateHandler()");
  }

  /**
   * Each kind of unguarded handler is reported on its own: a public handler of a {@code
   * RestController}, a package-private one, a handler of a plain {@code Controller} and STOMP
   * message handlers; the guarded ones and the public methods that are not handlers next to them
   * are not.
   */
  @Test
  void preAuthorizeRuleNamesEachUnguardedHandler() {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + "preauthorize");

    String report =
        String.join(
            "\n",
            SeedArchitectureRules.CONTROLLER_METHODS_ARE_PRE_AUTHORIZED
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails());

    assertThat(report)
        .contains("UnguardedRestController.list()")
        .contains("UnguardedRestController.packagePrivateHandler()")
        .contains("UnguardedController.page()")
        .contains("UnguardedMessageController.snapshot()")
        .contains("UnguardedMessageController.receive(")
        .doesNotContain("UnguardedMessageController.guardedSnapshot()")
        .doesNotContain("UnguardedMessageController.failed(")
        .doesNotContain("UnguardedRestController.guarded()")
        .doesNotContain("UnguardedRestController.guardedByMetaAnnotation()")
        .doesNotContain("UnguardedRestController.binder(")
        .doesNotContain("UnguardedRestController.failed(")
        .doesNotContain("UnguardedRestController.helper()");
  }

  /**
   * Each request body with an audit field is reported (a record component, and a field renamed by
   * {@code @JsonProperty}); a compliant body and a type that is only returned are not.
   */
  @Test
  void auditFieldRuleNamesEachRequestBodyWithAnAuditField() {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + "auditrequest");

    String report =
        String.join(
            "\n",
            SeedArchitectureRules.REQUEST_BODIES_CARRY_NO_AUDIT_FIELDS
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails());

    assertThat(report)
        .contains("AuditedThingController.create(")
        .contains("CreateAuditedRequest with the audit field createdBy")
        .contains("AuditedThingController.edit(")
        .contains("EditCommand with the audit field updatedBy")
        .doesNotContain("RenameRequest")
        .doesNotContain("AuditView");
  }

  /**
   * Each kind of hard delete is reported on its own: a declared {@code deleteById}, the deletes
   * inherited from {@code CrudRepository}, a {@code remove*} method and a {@code DELETE} query.
   */
  @Test
  void noDeleteRuleNamesEveryKindOfHardDelete() {
    JavaClasses fixtures =
        new ClassFileImporter()
            .importPackages(
                FIXTURES + "springdatadelete",
                FIXTURES + "springdatacrud",
                FIXTURES + "springdataremove",
                FIXTURES + "springdataquery");

    String report =
        String.join(
            "\n",
            SeedArchitectureRules.SPRING_DATA_DECLARES_NO_DELETE
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails());

    assertThat(report)
        .contains("DeletingThingRepository.deleteById(")
        .contains("CrudThingRepository")
        .contains("RemovingThingRepository.removeByName(")
        .contains("PurgingThingRepository.purge(");
  }

  /** The fixtures are test classes, which {@code ApplicationModules.of(Class)} skips by default. */
  private static ApplicationModules fixtureModules() {
    return ApplicationModules.of(FixtureApplication.class, INCLUDE_TEST_CLASSES);
  }

  @Test
  void featureModulesAreDetectedWithTheProductionConfiguration() {
    ApplicationModules modules = fixtureModules();

    assertThat(modules.stream().map(module -> module.getBasePackage().getName()))
        .containsExactlyInAnyOrder(
            "com.company.fixtures.modulith.feature.alpha",
            "com.company.fixtures.modulith.feature.beta",
            "com.company.fixtures.modulith.feature.delta");
    // Modulith 2.1 names a module by its path below the application package.
    assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
        .containsExactlyInAnyOrder("feature.alpha", "feature.beta", "feature.delta");
  }

  @Test
  void packageWithoutApplicationModuleAnnotationIsNotAModule() {
    ApplicationModules modules = fixtureModules();

    assertThat(modules.stream().map(module -> module.getBasePackage().getName()))
        .doesNotContain("com.company.fixtures.modulith.feature.gamma");
    assertThat(modules.getModuleByType("com.company.fixtures.modulith.feature.gamma.GammaHelper"))
        .isEmpty();
  }

  @Test
  void accessToAnotherModulesInternalPackageIsAViolation() {
    ApplicationModules modules = fixtureModules();

    assertThat(modules.detectViolations().getMessage()).contains("AlphaInternals");
  }

  @Test
  void dependencyOutsideAllowedDependenciesIsAViolation() {
    ApplicationModules modules = fixtureModules();

    assertThat(modules.detectViolations().getMessage()).contains("DeltaService");
  }

  /**
   * {@code ApplicationModules.of} caches its result and {@code verify()} only runs once per
   * instance, so the per-violation tests above use {@code detectViolations()}; this test proves
   * {@code verify()} itself fails the build.
   */
  @Test
  void verifyFailsOnTheFixtureViolations() {
    assertThatThrownBy(() -> fixtureModules().verify()).isInstanceOf(Violations.class);
  }
}
