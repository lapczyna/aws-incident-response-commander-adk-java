package com.lapczynski.commander.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the simulator itself.
 *
 * <p>Determinism gets the most attention here, because everything downstream depends on it: the
 * golden-scenario harness can only make exact assertions if the same fixture yields byte-identical
 * numbers on every run and every machine.
 */
class SimulatedSignalSourceTest {

  private static final Instant T0 = Instant.parse("2026-09-09T12:00:00Z");
  private static final ScenarioLibrary LIBRARY = new ScenarioLibrary();

  private static SimulatedSignalSource source(String scenarioId) {
    return new SimulatedSignalSource(new ScenarioRun(LIBRARY.require(scenarioId), T0));
  }

  private static TimeWindow twoHours() {
    return new TimeWindow(T0.minus(Duration.ofHours(2)), T0);
  }

  @Nested
  @DisplayName("determinism")
  class Determinism {

    @Test
    @DisplayName("two runs of the same scenario produce identical metric values")
    void identicalAcrossRuns() {
      var first =
          source("latency-after-bad-deployment")
              .query(
                  new MetricsPort.MetricQuery(
                      "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofMinutes(1)));
      var second =
          source("latency-after-bad-deployment")
              .query(
                  new MetricsPort.MetricQuery(
                      "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofMinutes(1)));

      assertThat(first.points())
          .as("golden assertions can only be exact if the numbers never move")
          .isEqualTo(second.points());
    }

    @Test
    @DisplayName("different metrics in one scenario get different jitter")
    void jitterVariesByMetric() {
      SimulatedSignalSource sim = source("latency-after-bad-deployment");
      var latency =
          sim.query(
              new MetricsPort.MetricQuery(
                  "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofMinutes(5)));
      var requests =
          sim.query(
              new MetricsPort.MetricQuery(
                  "checkout", "RequestCount", twoHours(), Duration.ofMinutes(5)));

      assertThat(latency.points().stream().map(MetricsPort.MetricPoint::value).toList())
          .as("seeding per metric avoids every series moving in lockstep, which would look fake")
          .isNotEqualTo(requests.points().stream().map(MetricsPort.MetricPoint::value).toList());
    }
  }

  @Nested
  @DisplayName("metrics")
  class Metrics {

    @Test
    @DisplayName("a step change is visible at the declared offset")
    void stepChangeAppears() {
      var series =
          source("latency-after-bad-deployment")
              .query(
                  new MetricsPort.MetricQuery(
                      "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofMinutes(1)));

      Instant changeAt = T0.minus(Duration.ofMinutes(25));
      double before =
          series.points().stream()
              .filter(p -> p.timestamp().isBefore(changeAt))
              .mapToDouble(MetricsPort.MetricPoint::value)
              .average()
              .orElseThrow();
      double after =
          series.points().stream()
              .filter(p -> !p.timestamp().isBefore(changeAt))
              .mapToDouble(MetricsPort.MetricPoint::value)
              .average()
              .orElseThrow();

      assertThat(before).isCloseTo(0.18, org.assertj.core.data.Offset.offset(0.03));
      assertThat(after).isCloseTo(0.62, org.assertj.core.data.Offset.offset(0.06));
      assertThat(after / before).as("the regression should be unmistakable").isGreaterThan(2.5);
    }

    @Test
    @DisplayName("an unknown metric returns empty rather than failing")
    void unknownMetricIsEmptyNotAnError() {
      var series =
          source("false-alarm")
              .query(
                  new MetricsPort.MetricQuery(
                      "checkout", "NoSuchMetric", twoHours(), Duration.ofMinutes(5)));

      assertThat(series.isEmpty())
          .as("'the source had no data' must be distinguishable from 'the source failed'")
          .isTrue();
    }

    @Test
    @DisplayName("a query that would exceed the datapoint cap is rejected at construction")
    void datapointCapIsEnforced() {
      assertThatThrownBy(
              () ->
                  new MetricsPort.MetricQuery(
                      "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("datapoints");
    }

    @Test
    @DisplayName("a window longer than the maximum lookback is rejected")
    void lookbackCapIsEnforced() {
      assertThatThrownBy(() -> new TimeWindow(T0.minus(Duration.ofDays(3)), T0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maximum lookback");
    }
  }

  @Nested
  @DisplayName("logs")
  class Logs {

    @Test
    @DisplayName("results are bounded and report truncation honestly")
    void resultsAreBoundedAndReportTruncation() {
      var result =
          source("errors-from-downstream-timeouts")
              .query(new LogsPort.LogQuery("checkout", "timed out", twoHours(), 10));

      assertThat(result.entries()).hasSize(10);
      assertThat(result.truncated())
          .as("a conclusion drawn from a partial view must be able to say it was partial")
          .isTrue();
      assertThat(result.totalMatched()).isGreaterThan(10);
    }

    @Test
    @DisplayName("a pattern matching nothing returns empty and is not truncated")
    void noMatchesIsEmpty() {
      var result =
          source("false-alarm")
              .query(new LogsPort.LogQuery("checkout", "kernel panic", twoHours(), 20));

      assertThat(result.isEmpty()).isTrue();
      assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("an over-large limit is rejected rather than silently clamped")
    void limitIsValidated() {
      assertThatThrownBy(
              () -> new LogsPort.LogQuery("checkout", ".*", twoHours(), LogsPort.MAX_ENTRIES + 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a malformed pattern is a bad request, not an evidence gap")
    void malformedPatternIsArgumentError() {
      assertThatThrownBy(
              () ->
                  source("false-alarm")
                      .query(new LogsPort.LogQuery("checkout", "[unclosed", twoHours(), 10)))
          .as("treating this as a source outage would quietly weaken the investigation")
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("scripted failures")
  class ScriptedFailures {

    @Test
    @DisplayName("the logs source answers once, then fails as scripted")
    void failsAfterConfiguredCallCount() {
      SimulatedSignalSource sim = source("tool-failure-during-investigation");
      LogsPort.LogQuery query = new LogsPort.LogQuery("checkout", "slow", twoHours(), 20);

      assertThat(sim.query(query).entries())
          .as("the first call is expected to succeed")
          .isNotEmpty();

      assertThatThrownBy(() -> sim.query(query))
          .isInstanceOf(SignalSourceException.class)
          .hasMessageContaining("deadline");
    }

    @Test
    @DisplayName("the failure carries a reason a specialist can turn into an evidence gap")
    void failureCarriesGapReason() {
      SimulatedSignalSource sim = source("tool-failure-during-investigation");
      LogsPort.LogQuery query = new LogsPort.LogQuery("checkout", "slow", twoHours(), 20);
      sim.query(query);

      SignalSourceException thrown =
          org.assertj.core.api.Assertions.catchThrowableOfType(
              SignalSourceException.class, () -> sim.query(query));

      assertThat(thrown.reason())
          .as("this is what lets a failed specialist degrade the run instead of aborting it")
          .isEqualTo(EvidenceGap.Reason.TIMEOUT);
    }

    @Test
    @DisplayName("other sources keep working while one is failing")
    void otherSourcesAreUnaffected() {
      SimulatedSignalSource sim = source("tool-failure-during-investigation");
      LogsPort.LogQuery logQuery = new LogsPort.LogQuery("checkout", "slow", twoHours(), 20);
      sim.query(logQuery);
      assertThatThrownBy(() -> sim.query(logQuery)).isInstanceOf(SignalSourceException.class);

      assertThat(
              sim.query(
                      new MetricsPort.MetricQuery(
                          "checkout", "TargetResponseTimeP99", twoHours(), Duration.ofMinutes(5)))
                  .isEmpty())
          .as("a partial investigation must still be able to reach a conclusion")
          .isFalse();
      assertThat(sim.serviceState("checkout").runningCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("each run gets its own counters, so failures are reproducible")
    void countersAreScopedToARun() {
      LogsPort.LogQuery query = new LogsPort.LogQuery("checkout", "slow", twoHours(), 20);

      SimulatedSignalSource first = source("tool-failure-during-investigation");
      first.query(query);
      assertThatThrownBy(() -> first.query(query)).isInstanceOf(SignalSourceException.class);

      SimulatedSignalSource second = source("tool-failure-during-investigation");
      assertThat(second.query(query).entries())
          .as("a fresh run must behave identically, not inherit the previous run's call count")
          .isNotEmpty();
    }
  }

  @Nested
  @DisplayName("ECS, changes and versions")
  class OtherSources {

    @Test
    @DisplayName("a crash-looping service reports as degraded with a stopped reason")
    void crashLoopIsVisible() {
      EcsPort.ServiceState state = source("ecs-task-instability").serviceState("checkout");

      assertThat(state.isDegraded()).isTrue();
      assertThat(state.runningCount()).isEqualTo(1);
      assertThat(state.desiredCount()).isEqualTo(3);
      assertThat(state.tasks())
          .filteredOn(task -> !task.isHealthy())
          .extracting(EcsPort.Task::stoppedReason)
          .as("stoppedReason is usually the decisive signal in an ECS incident")
          .allMatch(reason -> reason.contains("OutOfMemoryError"));
    }

    @Test
    @DisplayName("deployment-related changes are identifiable")
    void deploymentChangesAreFlagged() {
      List<ChangeHistoryPort.ChangeEvent> changes =
          source("latency-after-bad-deployment").recentChanges("checkout", twoHours(), 20);

      assertThat(changes).isNotEmpty();
      assertThat(changes).anyMatch(ChangeHistoryPort.ChangeEvent::isDeployment);
      assertThat(changes)
          .as("newest first, so an investigator sees the most recent change immediately")
          .isSortedAccordingTo(
              java.util.Comparator.comparing(ChangeHistoryPort.ChangeEvent::occurredAt).reversed());
    }

    @Test
    @DisplayName("the last known good version is the previous healthy one, not the current one")
    void lastKnownGoodSkipsCurrent() {
      var lastGood = source("latency-after-bad-deployment").lastKnownGood("checkout").orElseThrow();

      assertThat(lastGood.version()).isEqualTo("2.3.1");
      assertThat(lastGood.taskDefinition()).isEqualTo("checkout:41");
      assertThat(lastGood.current()).isFalse();
      assertThat(lastGood.healthy()).isTrue();
    }

    @Test
    @DisplayName("an INSUFFICIENT_DATA alarm is distinguishable from a healthy one")
    void insufficientDataIsNotOk() {
      List<AlarmsPort.AlarmState> alarms =
          source("contradictory-evidence").activeAlarms("checkout");

      assertThat(alarms).hasSize(1);
      assertThat(alarms.getFirst().hasNoData())
          .as("'the alarm says nothing is wrong' and 'the alarm has no data' are different claims")
          .isTrue();
      assertThat(alarms.getFirst().isFiring()).isFalse();
    }
  }

  @Nested
  @DisplayName("scenario time")
  class ScenarioTime {

    @Test
    @DisplayName("fixture offsets resolve relative to when the run started")
    void offsetsAreRelativeToRunStart() {
      Instant later = T0.plus(Duration.ofDays(30));
      var laterRun =
          new SimulatedSignalSource(
              new ScenarioRun(LIBRARY.require("latency-after-bad-deployment"), later));

      var changes =
          laterRun.recentChanges(
              "checkout", new TimeWindow(later.minus(Duration.ofHours(2)), later), 10);

      assertThat(changes.getFirst().occurredAt())
          .as("a fixture written months ago must still describe an incident happening now")
          .isEqualTo(later.minus(Duration.ofMinutes(25)));
    }
  }
}
