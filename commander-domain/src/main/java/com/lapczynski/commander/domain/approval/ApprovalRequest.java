package com.lapczynski.commander.domain.approval;

import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A durable request for a human to authorise one exact action.
 *
 * <p>Everything an approver needs to make an informed decision is captured at creation time and
 * frozen: the action, its target, the risk classification, the rationale, and the evidence the
 * rationale rests on. An approver must never have to go and ask the model what it meant.
 *
 * <p>The request survives a restart. When the process comes back, this row plus the persisted ADK
 * session is enough to resume the exact invocation that was waiting.
 *
 * @param fingerprint binds the approval to this action, this incident and this incident version
 * @param expiresAt after this instant the request is {@link ApprovalStatus#EXPIRED} and cannot be
 *     approved. An approval with no deadline is an approval that can be used months later against a
 *     system nobody remembers.
 */
public record ApprovalRequest(
    ApprovalId id,
    IncidentId incidentId,
    long incidentVersion,
    ProposedAction action,
    ActionFingerprint fingerprint,
    RiskLevel risk,
    String rationale,
    String expectedImpact,
    List<EvidenceId> supportingEvidence,
    ApprovalStatus status,
    Instant requestedAt,
    Instant expiresAt,
    Optional<String> adkFunctionCallId) {

  public ApprovalRequest {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    Objects.requireNonNull(risk, "risk must not be null");
    Objects.requireNonNull(rationale, "rationale must not be null");
    Objects.requireNonNull(expectedImpact, "expectedImpact must not be null");
    Objects.requireNonNull(supportingEvidence, "supportingEvidence must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Objects.requireNonNull(adkFunctionCallId, "adkFunctionCallId must not be null");
    if (incidentVersion < 0) {
      throw new IllegalArgumentException("incidentVersion must not be negative");
    }
    if (rationale.isBlank()) {
      throw new IllegalArgumentException("rationale must not be blank");
    }
    if (expectedImpact.isBlank()) {
      throw new IllegalArgumentException(
          "expectedImpact must not be blank; an approver has to know what this will do");
    }
    if (supportingEvidence.isEmpty()) {
      throw new IllegalArgumentException(
          "an approval request must cite the evidence it rests on; asking a human to approve "
              + "an action with no stated basis defeats the purpose of the gate");
    }
    if (!expiresAt.isAfter(requestedAt)) {
      throw new IllegalArgumentException("expiresAt must be after requestedAt");
    }
    supportingEvidence = List.copyOf(supportingEvidence);
  }

  /**
   * Creates a pending request whose fingerprint is derived from the action and incident version.
   */
  public static ApprovalRequest pending(
      ApprovalId id,
      IncidentId incidentId,
      long incidentVersion,
      ProposedAction action,
      RiskLevel risk,
      String rationale,
      String expectedImpact,
      List<EvidenceId> supportingEvidence,
      Instant requestedAt,
      Instant expiresAt,
      String adkFunctionCallId) {
    return new ApprovalRequest(
        id,
        incidentId,
        incidentVersion,
        action,
        ActionFingerprint.of(action, incidentId, incidentVersion),
        risk,
        rationale,
        expectedImpact,
        supportingEvidence,
        ApprovalStatus.PENDING,
        requestedAt,
        expiresAt,
        Optional.ofNullable(adkFunctionCallId));
  }

  /** Whether the deadline has passed as of {@code now}. */
  public boolean hasExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /**
   * Whether this request still matches the current state of its incident.
   *
   * <p>Recomputes the fingerprint from the incident's present version and compares in constant
   * time. A false result means the incident moved on and this approval is void.
   */
  public boolean stillMatches(long currentIncidentVersion) {
    ActionFingerprint current = ActionFingerprint.of(action, incidentId, currentIncidentVersion);
    return fingerprint.matches(current);
  }

  /**
   * Whether this request can be acted on right now.
   *
   * <p>All three conditions must hold: still pending, not expired, and not superseded by a change
   * to the incident. Checked together so no caller can satisfy one and forget another.
   */
  public boolean isActionable(Instant now, long currentIncidentVersion) {
    return status == ApprovalStatus.PENDING
        && !hasExpired(now)
        && stillMatches(currentIncidentVersion);
  }

  public ApprovalRequest withStatus(ApprovalStatus newStatus) {
    Objects.requireNonNull(newStatus, "status must not be null");
    if (status.isTerminal()) {
      throw new IllegalStateException(
          "approval %s is already %s and cannot become %s".formatted(id, status, newStatus));
    }
    return new ApprovalRequest(
        id,
        incidentId,
        incidentVersion,
        action,
        fingerprint,
        risk,
        rationale,
        expectedImpact,
        supportingEvidence,
        newStatus,
        requestedAt,
        expiresAt,
        adkFunctionCallId);
  }
}
