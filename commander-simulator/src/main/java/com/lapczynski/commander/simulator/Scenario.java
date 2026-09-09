package com.lapczynski.commander.simulator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reproducible incident, loaded from a version-controlled YAML fixture.
 *
 * <p>Scenarios are relative in time, not absolute. Every timestamp is expressed as an offset from
 * the moment the scenario is started ({@code t0}), so a fixture written last year still produces an
 * incident that looks like it is happening now. Absolute timestamps would make the fixtures rot and
 * force the metric windows to be rewritten constantly.
 *
 * <p>Offsets are negative for the past: {@code offsetMinutes: -30} is thirty minutes before the
 * scenario started.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Scenario(
    String id,
    String title,
    String description,
    @JsonProperty("expectedOutcome") ExpectedOutcome expectedOutcome,
    String serviceName,
    String environment,
    List<MetricFixture> metrics,
    List<LogFixture> logs,
    List<AlarmFixture> alarms,
    EcsFixture ecs,
    List<ChangeFixture> changes,
    List<VersionFixture> versions,
    Map<String, FailureFixture> failures) {

  /**
   * What a correct investigation should conclude.
   *
   * <p>Recorded in the fixture so the golden-scenario harness (ADR-0009) can assert on it rather
   * than the assertion living separately and drifting from the data.
   */
  public enum ExpectedOutcome {
    /** A cause is identifiable and a remediation should be proposed. */
    REMEDIATION_PROPOSED,
    /** No actionable incident; the investigation should close without proposing anything. */
    NO_ACTION_NEEDED,
    /** Evidence conflicts; the investigation should report low confidence and not act. */
    INCONCLUSIVE,
    /** Remediation is proposed and applied, but verification should fail. */
    VERIFICATION_FAILS
  }

  public Scenario {
    Objects.requireNonNull(id, "scenario id must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(serviceName, "serviceName must not be null");
    Objects.requireNonNull(expectedOutcome, "expectedOutcome must not be null");
    metrics = nullToEmpty(metrics);
    logs = nullToEmpty(logs);
    alarms = nullToEmpty(alarms);
    changes = nullToEmpty(changes);
    versions = nullToEmpty(versions);
    failures = failures == null ? Map.of() : Map.copyOf(failures);
    description = description == null ? "" : description;
    environment = environment == null ? "demo" : environment;
  }

  private static <T> List<T> nullToEmpty(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  /**
   * A metric series, described as a shape rather than as raw datapoints.
   *
   * <p>Writing out several hundred numbers per metric would make the fixtures unreadable and
   * unreviewable. Instead each fixture declares a baseline, an optional step change and when it
   * happens, and the generator produces the points deterministically.
   *
   * @param jitter fraction of the value to vary by, applied from a seed derived from the scenario
   *     id and metric name. Deterministic across runs: the same fixture always yields identical
   *     numbers, which is what lets golden assertions be exact.
   */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record MetricFixture(
      String name,
      String unit,
      double baseline,
      Double afterValue,
      Integer changeAtOffsetMinutes,
      double jitter) {

    public MetricFixture {
      Objects.requireNonNull(name, "metric name must not be null");
      unit = unit == null ? "None" : unit;
      if (jitter < 0.0 || jitter > 0.5) {
        throw new IllegalArgumentException("jitter must be within [0.0, 0.5], was " + jitter);
      }
      if (afterValue != null && changeAtOffsetMinutes == null) {
        throw new IllegalArgumentException(
            "metric '%s' declares afterValue but no changeAtOffsetMinutes".formatted(name));
      }
    }

    /** Whether this metric steps to a different level partway through the window. */
    public boolean hasStepChange() {
      return afterValue != null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record LogFixture(
      String level, String message, int offsetMinutes, Integer repeat, Map<String, String> fields) {

    public LogFixture {
      Objects.requireNonNull(message, "log message must not be null");
      level = level == null ? "INFO" : level;
      fields = fields == null ? Map.of() : Map.copyOf(fields);
      if (repeat != null && (repeat < 1 || repeat > 100)) {
        throw new IllegalArgumentException("repeat must be within [1, 100]");
      }
    }

    public int repeatCount() {
      return repeat == null ? 1 : repeat;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record AlarmFixture(
      String name,
      String state,
      String reason,
      String metricName,
      double threshold,
      int changedAtOffsetMinutes) {

    public AlarmFixture {
      Objects.requireNonNull(name, "alarm name must not be null");
      state = state == null ? "ALARM" : state;
      reason = reason == null ? "" : reason;
      metricName = metricName == null ? "" : metricName;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record EcsFixture(
      String serviceArn,
      int desiredCount,
      int runningCount,
      int pendingCount,
      List<TaskFixture> tasks,
      List<DeploymentFixture> deployments) {

    public EcsFixture {
      serviceArn = serviceArn == null ? "" : serviceArn;
      tasks = tasks == null ? List.of() : List.copyOf(tasks);
      deployments = deployments == null ? List.of() : List.copyOf(deployments);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record TaskFixture(
      String taskArn,
      String lastStatus,
      String healthStatus,
      String taskDefinition,
      int startedAtOffsetMinutes,
      String stoppedReason,
      int restartCount) {

    public TaskFixture {
      Objects.requireNonNull(taskArn, "taskArn must not be null");
      lastStatus = lastStatus == null ? "RUNNING" : lastStatus;
      healthStatus = healthStatus == null ? "HEALTHY" : healthStatus;
      taskDefinition = taskDefinition == null ? "unknown:1" : taskDefinition;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record DeploymentFixture(
      String id,
      String status,
      String taskDefinition,
      int desiredCount,
      int runningCount,
      int failedTasks,
      int createdAtOffsetMinutes) {

    public DeploymentFixture {
      Objects.requireNonNull(id, "deployment id must not be null");
      status = status == null ? "PRIMARY" : status;
      taskDefinition = taskDefinition == null ? "unknown:1" : taskDefinition;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ChangeFixture(
      String eventId,
      String eventName,
      String eventSource,
      String performedBy,
      String resourceArn,
      String detail,
      int offsetMinutes) {

    public ChangeFixture {
      Objects.requireNonNull(eventId, "change eventId must not be null");
      Objects.requireNonNull(eventName, "change eventName must not be null");
      eventSource = eventSource == null ? "ecs.amazonaws.com" : eventSource;
      performedBy = performedBy == null ? "unknown" : performedBy;
      resourceArn = resourceArn == null ? "" : resourceArn;
      detail = detail == null ? "" : detail;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record VersionFixture(
      String version,
      String taskDefinition,
      String deployedBy,
      boolean current,
      boolean healthy,
      int deployedAtOffsetMinutes) {

    public VersionFixture {
      Objects.requireNonNull(version, "version must not be null");
      taskDefinition = taskDefinition == null ? "unknown:1" : taskDefinition;
      deployedBy = deployedBy == null ? "ci" : deployedBy;
    }
  }

  /**
   * A deliberate failure of one signal source.
   *
   * <p>Scenario 7 exists to prove a failing investigator degrades the investigation rather than
   * aborting it, so the ability to make a port fail on demand is part of the fixture format rather
   * than something a test has to mock around.
   *
   * @param afterCalls fail only from this call number onward, so a scenario can succeed once and
   *     then start failing - which is what a rate limit or a mid-investigation outage looks like
   */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record FailureFixture(String reason, String message, Integer afterCalls) {

    public FailureFixture {
      Objects.requireNonNull(reason, "failure reason must not be null");
      message = message == null ? "simulated failure" : message;
    }

    public int failFromCall() {
      return afterCalls == null ? 0 : afterCalls;
    }
  }

  /** How far back a scenario's fixtures extend, used to size default query windows. */
  public Duration lookback() {
    return Duration.ofHours(2);
  }
}
