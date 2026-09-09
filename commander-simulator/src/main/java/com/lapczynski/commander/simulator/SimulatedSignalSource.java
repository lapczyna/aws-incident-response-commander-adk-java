package com.lapczynski.commander.simulator;

import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic implementation of every signal port, driven by a {@link ScenarioRun}.
 *
 * <p>Satisfies exactly the same interfaces as the AWS adapters, so the whole investigation workflow
 * runs identically with or without an AWS account. That is the property that makes the demo, the
 * golden-scenario harness and CI possible.
 *
 * <p><strong>Deterministic</strong> is the operative word. Metric jitter is generated from a seed
 * derived from the scenario id and metric name, never from {@code Math.random()} or the clock, so
 * the same fixture always produces the same numbers. Golden assertions can therefore be exact
 * rather than tolerance-based, and a failing test means something actually changed.
 */
public class SimulatedSignalSource
    implements MetricsPort,
        LogsPort,
        AlarmsPort,
        EcsPort,
        ChangeHistoryPort,
        DeploymentHistoryPort {

  /** Source names used for scripted failures in fixtures. */
  public static final String SOURCE_METRICS = "metrics";

  public static final String SOURCE_LOGS = "logs";
  public static final String SOURCE_ALARMS = "alarms";
  public static final String SOURCE_ECS = "ecs";
  public static final String SOURCE_CHANGES = "changes";
  public static final String SOURCE_VERSIONS = "versions";

  private final ScenarioRun run;

  public SimulatedSignalSource(ScenarioRun run) {
    this.run = run;
  }

  // ---------------------------------------------------------------- metrics

  @Override
  public MetricSeries query(MetricQuery query) {
    guard(SOURCE_METRICS);

    Optional<Scenario.MetricFixture> fixture =
        run.scenario().metrics().stream()
            .filter(m -> m.name().equals(query.metricName()))
            .findFirst();

    if (fixture.isEmpty()) {
      // The source worked and had nothing. Distinct from a failure to read, and the investigation
      // is expected to treat it that way.
      return new MetricSeries(query.metricName(), "None", List.of());
    }

    Scenario.MetricFixture metric = fixture.get();
    Instant changeAt =
        metric.hasStepChange() ? run.at(metric.changeAtOffsetMinutes()) : Instant.MAX;

    List<MetricPoint> points = new ArrayList<>();
    Random jitter = seededRandom(run.scenario().id() + ':' + metric.name());

    for (Instant t = query.window().from();
        t.isBefore(query.window().to());
        t = t.plus(query.period())) {
      double base = t.isBefore(changeAt) ? metric.baseline() : metric.afterValue();
      double offset =
          metric.jitter() == 0.0 ? 0.0 : (jitter.nextDouble() * 2 - 1) * metric.jitter();
      points.add(new MetricPoint(t, round(base * (1 + offset))));
    }

    return new MetricSeries(metric.name(), metric.unit(), points);
  }

  // ------------------------------------------------------------------- logs

  @Override
  public LogQueryResult query(LogQuery query) {
    guard(SOURCE_LOGS);

    Pattern pattern = compile(query.pattern());
    List<LogEntry> matched = new ArrayList<>();

    for (Scenario.LogFixture fixture : run.scenario().logs()) {
      Instant at = run.at(fixture.offsetMinutes());
      if (!query.window().contains(at)) {
        continue;
      }
      if (!pattern.matcher(fixture.message()).find()) {
        continue;
      }
      // A repeated line is emitted at one-second spacing so ordering stays stable and an
      // investigator can see a burst rather than one entry claiming to be many.
      for (int i = 0; i < fixture.repeatCount(); i++) {
        matched.add(
            new LogEntry(at.plusSeconds(i), fixture.level(), fixture.message(), fixture.fields()));
      }
    }

    matched.sort(Comparator.comparing(LogEntry::timestamp).reversed());

    long totalMatched = matched.size();
    boolean truncated = totalMatched > query.limit();
    return new LogQueryResult(
        matched.stream().limit(query.limit()).toList(), truncated, totalMatched);
  }

  // ----------------------------------------------------------------- alarms

  @Override
  public List<AlarmState> activeAlarms(String serviceName) {
    guard(SOURCE_ALARMS);

    return run.scenario().alarms().stream()
        .map(
            a ->
                new AlarmState(
                    a.name(),
                    a.state(),
                    a.reason(),
                    a.metricName(),
                    a.threshold(),
                    run.at(a.changedAtOffsetMinutes())))
        .limit(AlarmsPort.MAX_ALARMS)
        .toList();
  }

  // -------------------------------------------------------------------- ecs

  @Override
  public ServiceState serviceState(String serviceName) {
    guard(SOURCE_ECS);

    Scenario.EcsFixture ecs = run.scenario().ecs();
    if (ecs == null) {
      throw new SignalSourceException(
          EvidenceGap.Reason.NO_DATA, "scenario declares no ECS state for " + serviceName);
    }

    List<Task> tasks =
        ecs.tasks().stream()
            .map(
                t ->
                    new Task(
                        t.taskArn(),
                        t.lastStatus(),
                        t.healthStatus(),
                        t.taskDefinition(),
                        run.at(t.startedAtOffsetMinutes()),
                        t.stoppedReason(),
                        t.restartCount()))
            .limit(EcsPort.MAX_TASKS)
            .toList();

    return new ServiceState(
        serviceName,
        ecs.serviceArn(),
        ecs.desiredCount(),
        ecs.runningCount(),
        ecs.pendingCount(),
        tasks);
  }

  @Override
  public List<Deployment> deployments(String serviceName, int limit) {
    guard(SOURCE_ECS);

    Scenario.EcsFixture ecs = run.scenario().ecs();
    if (ecs == null) {
      return List.of();
    }

    return ecs.deployments().stream()
        .map(
            d ->
                new Deployment(
                    d.id(),
                    d.status(),
                    d.taskDefinition(),
                    d.desiredCount(),
                    d.runningCount(),
                    d.failedTasks(),
                    run.at(d.createdAtOffsetMinutes()),
                    run.at(d.createdAtOffsetMinutes())))
        .sorted(Comparator.comparing(Deployment::createdAt).reversed())
        .limit(Math.min(limit, EcsPort.MAX_DEPLOYMENTS))
        .toList();
  }

  // ---------------------------------------------------------------- changes

  @Override
  public List<ChangeEvent> recentChanges(String serviceName, TimeWindow window, int limit) {
    guard(SOURCE_CHANGES);

    return run.scenario().changes().stream()
        .filter(c -> window.contains(run.at(c.offsetMinutes())))
        .map(
            c ->
                new ChangeEvent(
                    c.eventId(),
                    c.eventName(),
                    c.eventSource(),
                    c.performedBy(),
                    c.resourceArn(),
                    c.detail(),
                    run.at(c.offsetMinutes())))
        .sorted(Comparator.comparing(ChangeEvent::occurredAt).reversed())
        .limit(Math.min(limit, ChangeHistoryPort.MAX_EVENTS))
        .toList();
  }

  // --------------------------------------------------------------- versions

  @Override
  public List<DeployedVersion> history(String serviceName, int limit) {
    guard(SOURCE_VERSIONS);

    return run.scenario().versions().stream()
        .map(
            v ->
                new DeployedVersion(
                    v.version(),
                    v.taskDefinition(),
                    v.deployedBy(),
                    v.current(),
                    v.healthy(),
                    run.at(v.deployedAtOffsetMinutes())))
        .sorted(Comparator.comparing(DeployedVersion::deployedAt).reversed())
        .limit(Math.min(limit, DeploymentHistoryPort.MAX_VERSIONS))
        .toList();
  }

  // ---------------------------------------------------------------- helpers

  /** Records the call and raises the scenario's scripted failure if one is due. */
  private void guard(String sourceName) {
    run.recordCall(sourceName);
    if (!run.shouldFail(sourceName)) {
      return;
    }
    Scenario.FailureFixture failure = run.failureFor(sourceName);
    throw new SignalSourceException(
        EvidenceGap.Reason.valueOf(failure.reason()), failure.message());
  }

  /**
   * Compiles a caller-supplied search pattern.
   *
   * <p>A malformed pattern is a bad request, not a source outage, so it surfaces as an argument
   * error rather than an evidence gap that would quietly weaken the investigation.
   */
  private static Pattern compile(String pattern) {
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("invalid log search pattern: " + pattern, e);
    }
  }

  /**
   * A generator seeded from stable text.
   *
   * <p>Deliberately not {@code Math.random()} or a time-based seed: the whole value of the
   * simulator is that a scenario produces identical numbers on every run and on every machine.
   */
  private static Random seededRandom(String seed) {
    return new Random(seed.hashCode());
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /** The window a scenario's fixtures are designed to cover, ending at the run's start. */
  public TimeWindow defaultWindow() {
    Duration lookback = run.scenario().lookback();
    return new TimeWindow(run.startedAt().minus(lookback), run.startedAt().plusSeconds(60));
  }
}
