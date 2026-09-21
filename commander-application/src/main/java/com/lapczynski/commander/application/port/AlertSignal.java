package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.Severity;
import java.util.Objects;

/**
 * The alert that opens an incident, and the numbers recovery will later be judged against.
 *
 * <p>The last two fields are the interesting ones. {@code observedValue} and {@code
 * recoveryThreshold} are captured <strong>when the incident is raised</strong>, before any model
 * has seen anything, because recovery is decided by comparing a later measurement against them
 * (ADR-0010). Deriving them afterwards — from the investigation, or worse from the narrative —
 * would let the thing being judged choose the criteria it is judged by.
 *
 * <p>An alert that carries neither is still accepted. It produces an incident that can be
 * investigated and remediated but whose recovery verifies as {@code INDETERMINATE}, which is the
 * correct answer to "did this work?" when nobody said what working would look like.
 *
 * @param metricName the metric that motivated the alert, and the one recovery is measured on
 * @param observedValue what that metric read when the alert fired
 * @param recoveryThreshold the value the metric must return below for the symptom to be gone
 */
public record AlertSignal(
    String title,
    String serviceName,
    String environment,
    Severity severity,
    String metricName,
    double observedValue,
    double recoveryThreshold,
    String description) {

  public AlertSignal {
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(serviceName, "serviceName must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    if (title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    if (serviceName.isBlank()) {
      throw new IllegalArgumentException("serviceName must not be blank");
    }
    metricName = metricName == null || metricName.isBlank() ? "" : metricName;
    description = description == null ? "" : description;
  }

  /** Whether recovery can be judged numerically at all. */
  public boolean isMeasurable() {
    return !metricName.isEmpty()
        && !Double.isNaN(observedValue)
        && !Double.isNaN(recoveryThreshold);
  }

  /** The text handed to the investigation as the incoming alert. */
  public String asAlertText() {
    StringBuilder text = new StringBuilder();
    text.append("Alert for ").append(serviceName).append(": ").append(title);
    if (isMeasurable()) {
      text.append("\nMetric ")
          .append(metricName)
          .append(" is ")
          .append(observedValue)
          .append(", which breaches the threshold of ")
          .append(recoveryThreshold)
          .append('.');
    }
    if (!description.isEmpty()) {
      text.append('\n').append(description);
    }
    return text.toString();
  }
}
