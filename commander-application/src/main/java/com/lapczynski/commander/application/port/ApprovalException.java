package com.lapczynski.commander.application.port;

/**
 * Why an approval could not be acted on.
 *
 * <p>A sealed hierarchy rather than one exception with a message, so the API layer maps each case
 * to a distinct response and no caller can accidentally treat "already decided" as "not found".
 * Each reason corresponds to an attack the approval workflow is meant to defeat.
 */
public sealed interface ApprovalException {

  /** The request does not exist. */
  record NotFound(String approvalId) implements ApprovalException {}

  /**
   * The incident changed since the request was raised, so the recomputed fingerprint differs.
   *
   * <p>The stale-approval defence. A human approved an action given the evidence they saw; the
   * evidence has since moved, and this is the system refusing to treat the old decision as covering
   * the new situation.
   */
  record Stale(String approvalId, String expectedFingerprint, String actualFingerprint)
      implements ApprovalException {}

  /** The deadline passed before anyone decided. */
  record Expired(String approvalId) implements ApprovalException {}

  /**
   * A decision already exists.
   *
   * <p>The replay defence. Approving twice must not execute twice, and this is where the second
   * attempt stops.
   */
  record AlreadyDecided(String approvalId, String existingOutcome) implements ApprovalException {}

  /** The actor may not approve: wrong role, or the same person who opened the incident. */
  record NotAuthorised(String actorId, String reason) implements ApprovalException {}

  /** Thrown form, for call sites that cannot return a result type. */
  final class Rejected extends RuntimeException {
    private final transient ApprovalException reason;

    public Rejected(ApprovalException reason) {
      super(reason.toString());
      this.reason = reason;
    }

    public ApprovalException reason() {
      return reason;
    }
  }
}
