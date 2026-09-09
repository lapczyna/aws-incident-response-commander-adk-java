package com.lapczynski.commander.domain.audit;

/**
 * The kinds of event recorded in the append-only audit log.
 *
 * <p>Scoped to things a reviewer would need after the fact: who did what, what was decided, and
 * what actually ran. Routine progress chatter belongs in metrics and traces, not here — an audit
 * log that records everything is one nobody reads.
 */
public enum AuditEventType {
  INCIDENT_OPENED,
  INCIDENT_STATUS_CHANGED,
  INCIDENT_CANCELLED,

  EVIDENCE_RECORDED,
  EVIDENCE_GAP_RECORDED,
  HYPOTHESIS_PROPOSED,
  HYPOTHESIS_REFINED,

  REMEDIATION_PROPOSED,
  POLICY_EVALUATED,
  POLICY_DENIED,

  APPROVAL_REQUESTED,
  APPROVAL_GRANTED,
  APPROVAL_REJECTED,
  APPROVAL_EXPIRED,
  APPROVAL_SUPERSEDED,
  /** A decision was attempted against an approval that no longer matched the incident. */
  APPROVAL_STALE_REJECTED,

  ACTION_EXECUTED,
  ACTION_SKIPPED_IDEMPOTENT,
  ACTION_FAILED,

  VERIFICATION_SUCCEEDED,
  VERIFICATION_FAILED,
  REPORT_GENERATED;

  /**
   * Whether this event concerns the authorisation path.
   *
   * <p>Used to produce the security-relevant subset of the audit trail without a reviewer having to
   * know which constants matter.
   */
  public boolean isSecurityRelevant() {
    return switch (this) {
      case POLICY_EVALUATED,
          POLICY_DENIED,
          APPROVAL_REQUESTED,
          APPROVAL_GRANTED,
          APPROVAL_REJECTED,
          APPROVAL_EXPIRED,
          APPROVAL_SUPERSEDED,
          APPROVAL_STALE_REJECTED,
          ACTION_EXECUTED,
          ACTION_SKIPPED_IDEMPOTENT,
          ACTION_FAILED ->
          true;
      default -> false;
    };
  }
}
