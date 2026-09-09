package com.lapczynski.commander.domain.remediation;

import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of an action having been attempted.
 *
 * <p>Separate from the idempotency claim on purpose. The claim answers "may I act?" and is keyed on
 * the fingerprint; this answers "what happened?" and is what a report and a human read. Merging
 * them would mean the exactly-once mechanism and the historical record could not evolve
 * independently, and the record is the one that needs to grow.
 *
 * @param approvalId the approval that authorised this. Optional only so that a change which has
 *     already happened is never left unrecorded because its approval could not be resolved
 *     afterwards; an empty value is an anomaly to investigate, not a permitted workflow.
 * @param dryRun whether this was simulated. Stored per execution rather than derived from
 *     configuration at read time, because configuration changes and a report that inferred this
 *     would eventually claim a real change was made when it was not.
 * @param detail what the executor reported, or why it failed
 */
public record ExecutedAction(
    UUID id,
    IncidentId incidentId,
    Optional<ApprovalId> approvalId,
    ActionFingerprint fingerprint,
    ActionType actionType,
    String targetArn,
    boolean dryRun,
    Outcome outcome,
    Optional<String> detail,
    Instant startedAt,
    Optional<Instant> finishedAt) {

  /** How the attempt ended. */
  public enum Outcome {
    SUCCEEDED,

    /**
     * The action threw partway through.
     *
     * <p>Its effect on the target is unknown, which is why nothing retries automatically.
     */
    FAILED,

    /** A replay. The fingerprint was already claimed, so nothing was done a second time. */
    SKIPPED_DUPLICATE
  }

  public ExecutedAction {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(approvalId, "approvalId must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    Objects.requireNonNull(actionType, "actionType must not be null");
    Objects.requireNonNull(targetArn, "targetArn must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(detail, "detail must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(finishedAt, "finishedAt must not be null");
  }

  /** Whether the action reached its target and reported success. */
  public boolean changedSomething() {
    return outcome == Outcome.SUCCEEDED && !dryRun;
  }

  /**
   * Whether recovery is worth measuring after this.
   *
   * <p>A dry run changed nothing, so verifying recovery afterwards would measure the weather. The
   * verifier consults this rather than assuming every execution warrants a metric query.
   */
  public boolean warrantsVerification() {
    return changedSomething();
  }
}
