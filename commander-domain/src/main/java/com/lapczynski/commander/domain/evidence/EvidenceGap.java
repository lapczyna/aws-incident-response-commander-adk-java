package com.lapczynski.commander.domain.evidence;

import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.Objects;

/**
 * A record that evidence which should exist could not be obtained.
 *
 * <p>This type exists so that a failed investigator degrades the investigation rather than aborting
 * it. When a CloudWatch query times out, the result is a gap, not an exception that unwinds the
 * whole ParallelAgent.
 *
 * <p>Gaps are reported in the final incident report alongside evidence, because the difference
 * between "the logs showed nothing" and "the logs could not be read" changes what a conclusion is
 * worth. Collapsing the two would let an investigation claim more certainty than it earned.
 */
public record EvidenceGap(
    IncidentId incidentId,
    EvidenceSource source,
    String attemptedBy,
    Reason reason,
    String detail,
    Instant recordedAt) {

  /** Why the evidence is missing. */
  public enum Reason {
    /** The upstream call exceeded its deadline. */
    TIMEOUT,
    /** The source returned an error. */
    UPSTREAM_ERROR,
    /** The source responded, but held no data for the window queried. */
    NO_DATA,
    /** The caller lacks permission to read this source. */
    ACCESS_DENIED,
    /** The tool-call budget for this investigation was exhausted first. */
    BUDGET_EXHAUSTED
  }

  public EvidenceGap {
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(attemptedBy, "attemptedBy must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(detail, "detail must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
  }

  /**
   * Whether this gap should lower confidence in any conclusion drawn without it.
   *
   * <p>{@link Reason#NO_DATA} is a genuine observation: the source worked and had nothing to say.
   * Every other reason means we simply do not know, which is a weaker position.
   */
  public boolean underminesConclusions() {
    return reason != Reason.NO_DATA;
  }
}
