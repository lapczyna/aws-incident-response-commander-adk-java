package com.lapczynski.commander.application.verification;

import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures whether the symptom went away, and moves the incident accordingly.
 *
 * <p>No model is involved in the verdict. Whether an incident is over decides whether a human stops
 * paying attention, so it is decided by comparing numbers against a threshold in Java. A model may
 * later describe what happened; it may not decide whether it worked.
 *
 * <p>Three defaults here are chosen against the direction that would flatter the system:
 *
 * <ul>
 *   <li><strong>A metric that cannot be read is {@code INDETERMINATE}, never recovered.</strong>
 *       The failure is recorded as an evidence gap so the report says why, and the incident goes to
 *       {@code FAILED} so a human picks it up.
 *   <li><strong>An empty series is also indeterminate.</strong> For most sources "the source
 *       answered and had nothing" supports an inference, but a service emitting no latency
 *       datapoints after a restart is more likely to be down than healthy.
 *   <li><strong>A settle window is skipped before measuring.</strong> The first datapoints after a
 *       task restart are dominated by cold starts, and averaging them in makes a working fix look
 *       like a failed one. Waiting is the caller's job; this class refuses to measure a window that
 *       begins before the action finished.
 * </ul>
 */
public class RecoveryVerifier {

  private static final Logger log = LoggerFactory.getLogger(RecoveryVerifier.class);

  /** Who this service is, in the audit trail and on the evidence it records. */
  private static final String COLLECTOR = "recovery-verifier";

  /**
   * How long after an action to ignore before measuring.
   *
   * <p>Long enough for replacement tasks to warm up, short enough that an operator watching the UI
   * does not conclude the system has hung.
   */
  public static final Duration DEFAULT_SETTLE = Duration.ofMinutes(2);

  /** How much post-settle data to average over. */
  public static final Duration DEFAULT_OBSERVATION = Duration.ofMinutes(5);

  private final MetricsPort metrics;
  private final EvidenceRepository evidence;
  private final ExecutionRepository executions;
  private final VerificationRepository verifications;
  private final IncidentRepository incidents;
  private final AuditLog auditLog;
  private final Clock clock;

  public RecoveryVerifier(
      MetricsPort metrics,
      EvidenceRepository evidence,
      ExecutionRepository executions,
      VerificationRepository verifications,
      IncidentRepository incidents,
      AuditLog auditLog,
      Clock clock) {
    this.metrics = metrics;
    this.evidence = evidence;
    this.executions = executions;
    this.verifications = verifications;
    this.incidents = incidents;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * What to measure, and what counts as recovered.
   *
   * @param metricName the symptom metric, which must be the one that motivated the incident.
   *     Judging recovery on a different metric than the one that raised the alarm is how a system
   *     reports success for an incident it never addressed.
   * @param beforeValue the value that motivated the incident, carried forward from the
   *     investigation rather than re-derived, so before and after are comparable
   * @param recoveryThreshold the value at or below which the symptom is considered gone. Typically
   *     the alarm threshold that fired.
   */
  public record VerificationRequest(
      IncidentId incidentId,
      String serviceName,
      String metricName,
      double beforeValue,
      double recoveryThreshold,
      Duration settle,
      Duration observation) {

    public VerificationRequest {
      java.util.Objects.requireNonNull(incidentId, "incidentId must not be null");
      java.util.Objects.requireNonNull(serviceName, "serviceName must not be null");
      java.util.Objects.requireNonNull(metricName, "metricName must not be null");
      if (settle.isNegative()) {
        throw new IllegalArgumentException("settle must not be negative");
      }
      if (observation.isZero() || observation.isNegative()) {
        throw new IllegalArgumentException("observation window must be positive");
      }
    }

    public static VerificationRequest of(
        IncidentId incidentId,
        String serviceName,
        String metricName,
        double beforeValue,
        double recoveryThreshold) {
      return new VerificationRequest(
          incidentId,
          serviceName,
          metricName,
          beforeValue,
          recoveryThreshold,
          DEFAULT_SETTLE,
          DEFAULT_OBSERVATION);
    }
  }

  /**
   * Verifies recovery and transitions the incident to its terminal state.
   *
   * <p>The transition is part of this operation rather than left to a caller. A verification that
   * concluded {@code NOT_RECOVERED} and an incident still sitting in {@code VERIFYING} is the exact
   * state in which a failed remediation is quietly forgotten.
   */
  public RecoveryVerification verify(VerificationRequest request) {
    Incident incident =
        incidents
            .findById(request.incidentId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "cannot verify recovery for unknown incident " + request.incidentId()));

    if (incident.status() != IncidentStatus.VERIFYING) {
      throw new IllegalStateException(
          "recovery may only be verified while an incident is VERIFYING; it is %s"
              .formatted(incident.status()));
    }

    Instant now = clock.instant();
    Optional<ExecutedAction> execution = executions.findLatestForIncident(request.incidentId());

    // The observation window must lie entirely after the action settled. Measuring across the
    // moment of the change averages the broken state into the result, which makes a working fix
    // look partial and a failed one look better than it is.
    Instant readyAt =
        execution
            .flatMap(ExecutedAction::finishedAt)
            .map(finished -> finished.plus(request.settle()).plus(request.observation()))
            .orElse(Instant.MIN);
    if (now.isBefore(readyAt)) {
      throw new TooSoonToVerifyException(readyAt);
    }

    Measurement measurement = measure(request, now);

    RecoveryVerification.Outcome outcome =
        RecoveryVerification.judge(
            request.beforeValue(), measurement.value(), request.recoveryThreshold());

    RecoveryVerification verification =
        new RecoveryVerification(
            request.incidentId(),
            request.metricName(),
            request.beforeValue(),
            measurement.value(),
            request.recoveryThreshold(),
            outcome,
            describe(request, measurement, outcome),
            measurement.evidenceIds(),
            now);

    verifications.save(verification, execution.map(ExecutedAction::id));

    auditLog.append(
        AuditEvent.of(
            request.incidentId(),
            verification.permitsResolution()
                ? AuditEventType.VERIFICATION_SUCCEEDED
                : AuditEventType.VERIFICATION_FAILED,
            Actor.SYSTEM,
            verification.summary(),
            Map.of(
                "metric", request.metricName(),
                "before", String.valueOf(request.beforeValue()),
                "after", String.valueOf(measurement.value()),
                "threshold", String.valueOf(request.recoveryThreshold()),
                "outcome", outcome.name()),
            now));

    IncidentStatus target =
        verification.permitsResolution() ? IncidentStatus.RESOLVED : IncidentStatus.FAILED;
    Incident closed = incident.close(target, verification.summary(), now);
    incidents.update(closed, incident.version(), Actor.SYSTEM, "recovery verification: " + outcome);

    log.info(
        "Recovery verification complete: incident={} metric={} before={} after={} outcome={} "
            + "status={}",
        request.incidentId(),
        request.metricName(),
        request.beforeValue(),
        measurement.value(),
        outcome,
        target);

    return verification;
  }

  // ------------------------------------------------------------------ measuring

  /** The post-remediation value, and the stored observations backing it. */
  private record Measurement(double value, List<EvidenceId> evidenceIds) {}

  private Measurement measure(VerificationRequest request, Instant now) {
    Instant windowEnd = now;
    Instant windowStart = windowEnd.minus(request.observation());
    TimeWindow window = new TimeWindow(windowStart, windowEnd);

    // Period chosen so the window yields a handful of datapoints rather than hundreds: enough to
    // average out noise, few enough that one anomalous minute cannot dominate.
    Duration period = Duration.ofMinutes(1);

    MetricsPort.MetricSeries series;
    try {
      series =
          metrics.query(
              new MetricsPort.MetricQuery(
                  request.serviceName(), request.metricName(), window, period));
    } catch (SignalSourceException e) {
      // Recorded, not swallowed. The report must be able to say the metric could not be read,
      // because that changes what the verdict is worth.
      evidence.saveGap(
          new EvidenceGap(
              request.incidentId(),
              EvidenceSource.CLOUDWATCH_METRICS,
              COLLECTOR,
              e.reason(),
              "Could not read %s while verifying recovery".formatted(request.metricName()),
              now));

      log.warn(
          "Recovery metric unavailable: incident={} metric={} reason={}",
          request.incidentId(),
          request.metricName(),
          e.reason());

      return new Measurement(Double.NaN, List.of(recordUnmeasurable(request, now, e.reason())));
    }

    if (series.isEmpty()) {
      evidence.saveGap(
          new EvidenceGap(
              request.incidentId(),
              EvidenceSource.CLOUDWATCH_METRICS,
              COLLECTOR,
              EvidenceGap.Reason.NO_DATA,
              "%s returned no datapoints after remediation".formatted(request.metricName()),
              now));

      return new Measurement(
          Double.NaN, List.of(recordUnmeasurable(request, now, EvidenceGap.Reason.NO_DATA)));
    }

    double after = series.average();

    EvidenceId id = EvidenceId.newId();
    evidence.save(
        new Evidence(
            id,
            request.incidentId(),
            EvidenceSource.CLOUDWATCH_METRICS,
            COLLECTOR,
            "%s averaged %s over %s after remediation (was %s, recovery threshold %s)"
                .formatted(
                    request.metricName(),
                    round(after),
                    request.observation(),
                    round(request.beforeValue()),
                    round(request.recoveryThreshold())),
            renderSeries(series),
            // Metrics are numeric aggregates from a source that cannot carry engineered text, and
            // the measurement is the thing being judged, so it is recorded as certain. The
            // uncertainty lives in the verdict, not in whether the number was read correctly.
            Confidence.CERTAIN,
            now,
            Optional.of(windowEnd)));

    return new Measurement(after, List.of(id));
  }

  /**
   * Records the absence of a measurement as evidence in its own right.
   *
   * <p>A {@link RecoveryVerification} must cite something, and "we tried to measure and could not"
   * is the honest citation for an indeterminate verdict. Fabricating a value to satisfy the
   * constructor would be the alternative, which is exactly the mistake the constructor exists to
   * prevent.
   */
  private EvidenceId recordUnmeasurable(
      VerificationRequest request, Instant now, EvidenceGap.Reason reason) {

    EvidenceId id = EvidenceId.newId();
    evidence.save(
        new Evidence(
            id,
            request.incidentId(),
            EvidenceSource.CLOUDWATCH_METRICS,
            COLLECTOR,
            "%s could not be measured after remediation (%s)"
                .formatted(request.metricName(), reason),
            "No post-remediation datapoints were obtained for %s. Reason: %s."
                .formatted(request.metricName(), reason),
            Confidence.ZERO,
            now,
            Optional.empty()));
    return id;
  }

  private static String describe(
      VerificationRequest request, Measurement measurement, RecoveryVerification.Outcome outcome) {

    return switch (outcome) {
      case RECOVERED ->
          "%s is back to %s, at or below the recovery threshold of %s."
              .formatted(
                  request.metricName(),
                  round(measurement.value()),
                  round(request.recoveryThreshold()));
      case NOT_RECOVERED ->
          "%s is still %s against a recovery threshold of %s. The action completed, but the "
                  .formatted(
                      request.metricName(),
                      round(measurement.value()),
                      round(request.recoveryThreshold()))
              + "symptom persists, which means the diagnosis was wrong rather than the action.";
      case PARTIALLY_RECOVERED ->
          "%s improved from %s to %s but is still above the recovery threshold of %s. A human "
                  .formatted(
                      request.metricName(),
                      round(request.beforeValue()),
                      round(measurement.value()),
                      round(request.recoveryThreshold()))
              + "decides whether that is good enough.";
      case INDETERMINATE ->
          "%s could not be measured after remediation, so recovery is unknown. This is not "
                  .formatted(request.metricName())
              + "the same as the fix having failed, and it is not the same as it having worked.";
    };
  }

  /** A compact rendering of the series, stored as the evidence content a human can check. */
  private static String renderSeries(MetricsPort.MetricSeries series) {
    StringBuilder content = new StringBuilder(256);
    content
        .append(series.metricName())
        .append(" (")
        .append(series.unit())
        .append("), ")
        .append(series.points().size())
        .append(" datapoints:\n");

    for (MetricsPort.MetricPoint point : series.points()) {
      content.append(point.timestamp()).append("  ").append(round(point.value())).append('\n');
    }
    content.append("average=").append(round(series.average()));
    content.append(" max=").append(round(series.max()));
    return content.toString();
  }

  private static String round(double value) {
    return Double.isNaN(value) ? "unmeasured" : String.valueOf(Math.round(value * 1000.0) / 1000.0);
  }
}
