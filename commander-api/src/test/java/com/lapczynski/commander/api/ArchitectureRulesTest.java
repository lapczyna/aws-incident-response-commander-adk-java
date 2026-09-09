package com.lapczynski.commander.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import java.util.LinkedHashSet;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable architecture. These rules are the reason the module boundaries in the README stay true
 * as the project grows, rather than decaying into a diagram nobody enforces.
 *
 * <p>They live in commander-api because it is the only module that depends on every other one, so
 * the importer sees the whole graph.
 */
class ArchitectureRulesTest {

  private static final String ROOT = "com.lapczynski.commander";

  private static JavaClasses productionClasses;

  /**
   * Excludes test output explicitly. {@code ImportOption.Predefined.DO_NOT_INCLUDE_TESTS} relies on
   * path heuristics that did not match this Windows/Maven layout, which let this very test class
   * count as production code. An explicit location filter is portable and obvious.
   *
   * <p>Note the absence of {@code DO_NOT_INCLUDE_JARS}: during {@code mvn verify} the sibling
   * modules are already packaged, so excluding jars silently reduced these rules to checking
   * commander-api alone. Restricting the import to {@link #ROOT} is what keeps third-party code
   * out, not a jar filter.
   */
  private static final ImportOption NOT_TEST_OUTPUT =
      location -> !location.contains("/test-classes/");

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(NOT_TEST_OUTPUT)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
  }

  @Test
  @DisplayName("layers depend only inwards: api -> adapters -> application -> domain")
  void layeredArchitectureIsRespected() {
    Architectures.layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("Domain")
        .definedBy(ROOT + ".domain..")
        .layer("Application")
        .definedBy(ROOT + ".application..")
        .layer("Adapters")
        .definedBy(ROOT + ".adk..", ROOT + ".persistence..", ROOT + ".aws..", ROOT + ".simulator..")
        .layer("Api")
        .definedBy(ROOT + ".api..")
        .whereLayer("Api")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Adapters")
        .mayOnlyBeAccessedByLayers("Api")
        .whereLayer("Application")
        .mayOnlyBeAccessedByLayers("Adapters", "Api")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers("Application", "Adapters", "Api")
        .check(productionClasses);
  }

  @Test
  @DisplayName("the domain stays framework-free")
  void domainHasNoFrameworkDependencies() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.google.adk..",
            "software.amazon..",
            "jakarta.persistence..",
            "java.sql..",
            "javax.sql..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "io.reactivex..")
        .because(
            "the domain owns every decision an LLM must not make; it must remain exhaustively "
                + "unit-testable without a container, a database or a network")
        .check(productionClasses);
  }

  @Test
  @DisplayName("the application layer declares ports and stays free of infrastructure")
  void applicationHasNoInfrastructureDependencies() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.google.adk..", "software.amazon..", "java.sql..", "org.springframework.jdbc..")
        .because(
            "application services talk to ports; ADK, the AWS SDK and JDBC belong behind adapters")
        .check(productionClasses);
  }

  @Test
  @DisplayName("Google ADK types never escape commander-adk")
  void adkIsConfinedToItsModule() {
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".adk..", ROOT + ".persistence..", ROOT + ".testing..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.google.adk..")
        .because(
            "swapping or upgrading ADK must stay a local change; persistence is the documented "
                + "exception because it implements ADK's session, memory and artifact SPIs (ADR-0004)")
        .check(productionClasses);
  }

  @Test
  @DisplayName("the AWS SDK never escapes commander-integrations-aws")
  void awsSdkIsConfinedToItsModule() {
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".aws..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("software.amazon.awssdk..")
        .because("the simulator must be able to satisfy the same ports with no AWS SDK involved")
        .check(productionClasses);
  }

  @Test
  @DisplayName("the simulator never reaches for the AWS SDK")
  void simulatorIsPurelyLocal() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".simulator..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("software.amazon..")
        .because("the simulator exists so the full demo runs with no AWS account and no network")
        .check(productionClasses);
  }

  @Test
  @DisplayName("nothing logs through stdout")
  void noStandardStreamLogging() {
    com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(
        productionClasses);
  }

  @Test
  @DisplayName("no java.util.Date; incident timelines use java.time")
  void noLegacyDateTimeApi() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.util.Date")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.util.Calendar")
        .because("every incident timestamp must be an unambiguous java.time instant")
        .check(productionClasses);
  }

  @Test
  @DisplayName("domain types are not Spring-managed")
  void domainIsNotSpringManaged() {
    classes()
        .that()
        .resideInAPackage(ROOT + ".domain..")
        .should()
        .notBeAnnotatedWith("org.springframework.stereotype.Component")
        .andShould()
        .notBeAnnotatedWith("org.springframework.stereotype.Service")
        .because("domain objects are constructed, not injected")
        .check(productionClasses);
  }

  /**
   * Closes the hole opened by {@code archRule.failOnEmptyShould=false}.
   *
   * <p>With empty-should disabled, a rule naming a package that does not exist would pass
   * vacuously, so a typo could silently disable an architectural guarantee. This test asserts every
   * layer package actually contains classes.
   *
   * <p>{@link #BOOTSTRAP_EMPTY} lists the packages not yet populated. It is a forcing function, not
   * an excuse: as each implementation phase fills a module, this test fails until the corresponding
   * entry is deleted. The set must be empty by the end of Phase 3.
   */
  private static final Set<String> BOOTSTRAP_EMPTY =
      new LinkedHashSet<>(
          Set.of(
              // Phase 7 brings the AWS adapters; the last entry to go.
              ROOT + ".aws"));

  @Test
  @DisplayName("every declared layer package is populated (guards the empty-should relaxation)")
  void everyDeclaredLayerIsPopulated() {
    Set<String> declared =
        Set.of(
            ROOT + ".domain",
            ROOT + ".application",
            ROOT + ".adk",
            ROOT + ".persistence",
            ROOT + ".aws",
            ROOT + ".simulator",
            ROOT + ".api");

    SoftAssertions softly = new SoftAssertions();
    for (String pkg : declared) {
      boolean populated =
          productionClasses.stream()
              .filter(c -> !c.getName().endsWith(".package-info"))
              .anyMatch(c -> c.getPackageName().startsWith(pkg));
      if (BOOTSTRAP_EMPTY.contains(pkg)) {
        softly
            .assertThat(populated)
            .as(
                "%s is listed in BOOTSTRAP_EMPTY but now has classes - delete that entry to "
                    + "re-arm the architecture rules for this package",
                pkg)
            .isFalse();
      } else {
        softly
            .assertThat(populated)
            .as("%s is empty or misspelled, so its architecture rules pass vacuously", pkg)
            .isTrue();
      }
    }
    softly.assertAll();
  }
}
