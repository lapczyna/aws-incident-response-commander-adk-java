package com.lapczynski.commander.domain.approval;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A human's recorded decision on an {@link ApprovalRequest}.
 *
 * <p>Immutable and append-only. A decision is evidence about what a person did at a moment in time,
 * so it is never edited; a change of mind is a new decision on a new request.
 *
 * <p>The fingerprint is stored again here, alongside the request's own copy. That looks redundant
 * and is not: it records the fingerprint <em>as it stood when the human clicked approve</em>. If
 * the request were somehow altered afterwards, the audit trail still shows exactly what was
 * authorised.
 */
public record ApprovalDecision(
    ApprovalId approvalId,
    Actor decidedBy,
    Outcome outcome,
    ActionFingerprint fingerprintAtDecision,
    Instant decidedAt,
    Optional<String> comment) {

  /** What the human decided. */
  public enum Outcome {
    APPROVED,
    REJECTED
  }

  public ApprovalDecision {
    Objects.requireNonNull(approvalId, "approvalId must not be null");
    Objects.requireNonNull(decidedBy, "decidedBy must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(fingerprintAtDecision, "fingerprintAtDecision must not be null");
    Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    Objects.requireNonNull(comment, "comment must not be null");

    if (!decidedBy.role().canApprove()) {
      throw new IllegalArgumentException(
          "%s holds role %s and cannot decide approvals"
              .formatted(decidedBy.id(), decidedBy.role()));
    }
    if (decidedBy.isSystem()) {
      throw new IllegalArgumentException(
          "the system cannot approve its own remediation; approval requires a human actor");
    }
  }

  public static ApprovalDecision approve(
      ApprovalId approvalId,
      Actor approver,
      ActionFingerprint fingerprint,
      Instant now,
      String comment) {
    return new ApprovalDecision(
        approvalId, approver, Outcome.APPROVED, fingerprint, now, Optional.ofNullable(comment));
  }

  public static ApprovalDecision reject(
      ApprovalId approvalId,
      Actor approver,
      ActionFingerprint fingerprint,
      Instant now,
      String comment) {
    return new ApprovalDecision(
        approvalId, approver, Outcome.REJECTED, fingerprint, now, Optional.ofNullable(comment));
  }

  public boolean isApproval() {
    return outcome == Outcome.APPROVED;
  }
}
