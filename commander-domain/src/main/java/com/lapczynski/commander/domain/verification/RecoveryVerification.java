package com.lapczynski.commander.domain.verification;

import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Whether a remediation actually worked.
 *
 * <p>This type exists because "we did the thing we said we would" and "the problem went away" are
 * different claims, and a system that conflates them will report success for a rollback that
 * changed nothing. Scenario 8 in the simulator is built entirely around that case: a convincing
 * diagnosis, a reasonable fix, and a service that stays broken because the real cause was
 * elsewhere.
 *
 * <p>The comparison is explicit rather than a verdict. Storing the before and after values, and the
 * threshold they were judged against, means a human reading the postmortem can disagree with the
 * conclusion without re-running anything.
 *
 * @param metricName the metric used to judge recovery, chosen because it was the symptom
 * @param beforeValue the value that motivated the incident
 * @param afterValue the value observed after remediation
 * @param recoveryThreshold the value at or below which the service is considered recovered
 * @param supportingEvidence the post-remediation observations this rests on. Never empty: a
 *     verification with no evidence is an opinion.
 */
public record RecoveryVerification(
    IncidentId incidentId,
    String metricName,
    double beforeValue,
    double afterValue,
    double recoveryThreshold,
    Outcome outcome,
    String summary,
    List<EvidenceId> supportingEvidence,
    Instant verifiedAt) {

  /** What the post-remediation evidence showed. */
  public enum Outcome {
    /** The symptom is gone. The incident can be resolved. */
    RECOVERED,

    /**
     * The action completed but the symptom persists.
     *
     * <p>The important one. It means the diagnosis was wrong, not that the action failed, and the
     * incident must move to FAILED rather than RESOLVED so a human picks it up.
     */
    NOT_RECOVERED,

    /**
     * The action improved things without fully resolving them.
     *
     * <p>Kept distinct from both because it is the honest answer surprisingly often, and reporting
     * it as either success or failure would mislead. A human decides what to do next.
     */
    PARTIALLY_RECOVERED,

    /**
     * Recovery could not be judged, usually because the metric was unavailable.
     *
     * <p>Explicitly not RECOVERED. Treating "we could not measure" as success is the single most
     * dangerous defaulting mistake available in this system.
     */
    INDETERMINATE
  }

  public RecoveryVerification {
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(metricName, "metricName must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
    Objects.requireNonNull(supportingEvidence, "supportingEvidence must not be null");

    if (summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    if (supportingEvidence.isEmpty()) {
      throw new IllegalArgumentException(
          "a verification must cite the observations it rests on; without them it is an opinion "
              + "about whether the incident is over");
    }
    supportingEvidence = List.copyOf(supportingEvidence);
  }

  /**
   * Judges recovery from measured values.
   *
   * <p>Deterministic, and deliberately not left to the model. Whether an incident is over decides
   * whether a human stops paying attention, and that is not a judgement to delegate to prose.
   *
   * @param afterValue the post-remediation value, or {@code NaN} when it could not be measured
   */
  public static Outcome judge(double beforeValue, double afterValue, double recoveryThreshold) {
    if (Double.isNaN(afterValue)) {
      return Outcome.INDETERMINATE;
    }
    if (afterValue <= recoveryThreshold) {
      return Outcome.RECOVERED;
    }

    // Improvement is measured against the distance actually travelled back towards the threshold,
    // not against the raw before/after ratio. A metric that fell from 100x normal to 50x normal
    // has halved and is still catastrophic; this treats that as not recovered.
    double excessBefore = beforeValue - recoveryThreshold;
    double excessAfter = afterValue - recoveryThreshold;

    if (excessBefore > 0 && excessAfter < excessBefore * 0.5) {
      return Outcome.PARTIALLY_RECOVERED;
    }
    return Outcome.NOT_RECOVERED;
  }

  /** Whether the incident may be resolved on the strength of this. */
  public boolean permitsResolution() {
    return outcome == Outcome.RECOVERED;
  }

  /** Whether a human needs to look at this before anything else happens. */
  public boolean requiresHumanAttention() {
    return outcome == Outcome.NOT_RECOVERED
        || outcome == Outcome.INDETERMINATE
        || outcome == Outcome.PARTIALLY_RECOVERED;
  }
}
