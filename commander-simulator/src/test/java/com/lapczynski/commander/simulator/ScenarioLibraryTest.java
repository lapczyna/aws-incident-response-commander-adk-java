package com.lapczynski.commander.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the fixture set itself.
 *
 * <p>Fixtures are data, and data rots quietly. Because {@code FAIL_ON_UNKNOWN_PROPERTIES} is
 * enabled, a mistyped key fails these tests rather than producing a scenario that silently omits a
 * signal and an investigation that plausibly finds nothing.
 */
class ScenarioLibraryTest {

  private static final ScenarioLibrary LIBRARY = new ScenarioLibrary();

  static List<Scenario> allScenarios() {
    return LIBRARY.all();
  }

  @Test
  @DisplayName("all nine scenarios load")
  void allScenariosLoad() {
    assertThat(LIBRARY.ids())
        .containsExactly(
            "contradictory-evidence",
            "db-connection-pool-exhaustion",
            "ecs-task-instability",
            "errors-from-downstream-timeouts",
            "false-alarm",
            "latency-after-bad-deployment",
            "prompt-injection-in-logs",
            "remediation-fails-verification",
            "tool-failure-during-investigation");
  }

  @Test
  @DisplayName("every expected outcome is represented, so the suite is not all happy paths")
  void everyOutcomeIsCovered() {
    assertThat(LIBRARY.all())
        .extracting(Scenario::expectedOutcome)
        .as(
            "a suite that only contains solvable incidents would not test the interesting "
                + "behaviour: declining to act, admitting uncertainty, and catching a failed fix")
        .contains(
            Scenario.ExpectedOutcome.REMEDIATION_PROPOSED,
            Scenario.ExpectedOutcome.NO_ACTION_NEEDED,
            Scenario.ExpectedOutcome.INCONCLUSIVE,
            Scenario.ExpectedOutcome.VERIFICATION_FAILS);
  }

  @ParameterizedTest
  @MethodSource("allScenarios")
  @DisplayName("each scenario is well formed and usable")
  void scenarioIsWellFormed(Scenario scenario) {
    SoftAssertions softly = new SoftAssertions();

    softly.assertThat(scenario.id()).as("id").isNotBlank();
    softly.assertThat(scenario.title()).as("title").isNotBlank();
    softly
        .assertThat(scenario.description())
        .as("%s: description explains what a correct investigation should conclude", scenario.id())
        .isNotBlank();
    softly.assertThat(scenario.serviceName()).as("%s: serviceName", scenario.id()).isNotBlank();
    softly
        .assertThat(scenario.metrics())
        .as(
            "%s: a scenario with no metrics gives an investigation nothing to start from",
            scenario.id())
        .isNotEmpty();

    softly.assertAll();
  }

  @ParameterizedTest
  @MethodSource("allScenarios")
  @DisplayName("scripted failures name a real EvidenceGap reason")
  void failureReasonsAreValid(Scenario scenario) {
    scenario
        .failures()
        .forEach(
            (source, failure) ->
                assertThat(
                        java.util.Arrays.stream(
                                com.lapczynski.commander.domain.evidence.EvidenceGap.Reason
                                    .values())
                            .map(Enum::name))
                    .as(
                        "%s: failure for '%s' names reason '%s'",
                        scenario.id(), source, failure.reason())
                    .contains(failure.reason()));
  }

  @Test
  @DisplayName("a metric declaring afterValue must say when the change happens")
  void stepChangesAreFullySpecified() {
    SoftAssertions softly = new SoftAssertions();
    for (Scenario scenario : LIBRARY.all()) {
      for (Scenario.MetricFixture metric : scenario.metrics()) {
        if (metric.hasStepChange()) {
          softly
              .assertThat(metric.changeAtOffsetMinutes())
              .as("%s / %s", scenario.id(), metric.name())
              .isNotNull()
              .isLessThan(0);
        }
      }
    }
    softly.assertAll();
  }

  @Test
  @DisplayName("an unknown scenario id fails with a message that lists what is available")
  void unknownScenarioIsHelpful() {
    assertThat(LIBRARY.find("no-such-scenario")).isEmpty();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> LIBRARY.require("no-such-scenario")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latency-after-bad-deployment");
  }
}
