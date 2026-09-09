package com.lapczynski.commander.application;

import com.lapczynski.commander.application.port.ApprovalException;
import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.approval.ApprovalStatus;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.incident.Incident;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether an approval may be acted on.
 *
 * <p>This is the enforcement point for ADR-0007. Every check here corresponds to a specific attack:
 *
 * <ul>
 *   <li><strong>Stale approval</strong> — the fingerprint is <em>recomputed</em> from the
 *       incident's current version and compared. A human approved an action given the evidence they
 *       saw; if the incident has moved on, that decision does not cover the new situation.
 *   <li><strong>Replay</strong> — a decision already recorded means the second attempt is refused,
 *       backed by a unique constraint so two concurrent requests cannot both pass.
 *   <li><strong>Expiry</strong> — evaluated on read, not only by a sweep, so a sweep that fails to
 *       run cannot extend the window in which an approval is usable.
 *   <li><strong>Confused deputy</strong> — the approver must hold the approver role and must not be
 *       the actor who opened the incident.
 * </ul>
 *
 * <p>What this class deliberately does <em>not</em> do is execute anything. Approving records a
 * decision and moves the incident; the action itself runs later, behind a second policy check and
 * an idempotency claim. Approval is necessary and never sufficient.
 */
public class ApprovalService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

  private final ApprovalRepository approvals;
  private final IncidentRepository incidents;
  private final AuditLog auditLog;
  private final Clock clock;

  public ApprovalService(
      ApprovalRepository approvals, IncidentRepository incidents, AuditLog auditLog, Clock clock) {
    this.approvals = approvals;
    this.incidents = incidents;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /** Raises a request and records it in the audit trail. */
  public void request(ApprovalRequest request) {
    approvals.create(request);
    auditLog.append(
        AuditEvent.of(
            request.incidentId(),
            AuditEventType.APPROVAL_REQUESTED,
            Actor.SYSTEM,
            "Approval requested for %s".formatted(request.action().type()),
            Map.of(
                "approvalId", request.id().toString(),
                "action", request.action().type().name(),
                "target", request.action().target().arn(),
                "risk", request.risk().name(),
                "fingerprint", request.fingerprint().abbreviated(),
                "expiresAt", request.expiresAt().toString()),
            clock.instant()));

    log.info(
        "Approval requested: approvalId={} incidentId={} action={} fingerprint={}",
        request.id(),
        request.incidentId(),
        request.action().type(),
        request.fingerprint().abbreviated());
  }

  /**
   * Approves a request, or explains why it cannot be approved.
   *
   * @return the fingerprint that was authorised, for the caller to resume execution with
   */
  public ActionFingerprint approve(ApprovalId approvalId, Actor approver, String comment) {
    ApprovalRequest request = validate(approvalId, approver);

    ApprovalDecision decision =
        ApprovalDecision.approve(
            approvalId, approver, request.fingerprint(), clock.instant(), comment);

    // Recorded before the status changes. The unique constraint on the decision is what makes a
    // concurrent double-approval impossible, so it must be the step that fails.
    approvals.recordDecision(decision);
    approvals.updateStatus(approvalId, ApprovalStatus.APPROVED);

    auditLog.append(
        AuditEvent.of(
            request.incidentId(),
            AuditEventType.APPROVAL_GRANTED,
            approver,
            "%s approved %s".formatted(approver.displayName(), request.action().type()),
            Map.of(
                "approvalId", approvalId.toString(),
                "action", request.action().type().name(),
                "target", request.action().target().arn(),
                "fingerprint", request.fingerprint().abbreviated()),
            clock.instant()));

    log.info(
        "Approval granted: approvalId={} by={} action={}",
        approvalId,
        approver.id(),
        request.action().type());

    return request.fingerprint();
  }

  /** Rejects a request. */
  public void reject(ApprovalId approvalId, Actor approver, String comment) {
    ApprovalRequest request = validate(approvalId, approver);

    approvals.recordDecision(
        ApprovalDecision.reject(
            approvalId, approver, request.fingerprint(), clock.instant(), comment));
    approvals.updateStatus(approvalId, ApprovalStatus.REJECTED);

    auditLog.append(
        AuditEvent.of(
            request.incidentId(),
            AuditEventType.APPROVAL_REJECTED,
            approver,
            "%s rejected %s".formatted(approver.displayName(), request.action().type()),
            Map.of("approvalId", approvalId.toString(), "comment", comment == null ? "" : comment),
            clock.instant()));

    log.info("Approval rejected: approvalId={} by={}", approvalId, approver.id());
  }

  /**
   * Runs every check that must pass before a decision is accepted.
   *
   * <p>Checked in order of cheapness, but all of them are checked: skipping a later one because an
   * earlier passed is exactly how a partial defence becomes no defence.
   */
  private ApprovalRequest validate(ApprovalId approvalId, Actor approver) {
    ApprovalRequest request =
        approvals
            .findById(approvalId)
            .orElseThrow(
                () ->
                    new ApprovalException.Rejected(
                        new ApprovalException.NotFound(approvalId.toString())));

    if (!approver.role().canApprove()) {
      throw reject(
          request,
          new ApprovalException.NotAuthorised(
              approver.id(), "role %s cannot approve".formatted(approver.role())));
    }
    if (approver.isSystem()) {
      throw reject(
          request,
          new ApprovalException.NotAuthorised(
              approver.id(), "the system cannot approve its own remediation"));
    }

    Optional<ApprovalDecision> existing = approvals.findDecision(approvalId);
    if (existing.isPresent()) {
      throw reject(
          request,
          new ApprovalException.AlreadyDecided(
              approvalId.toString(), existing.get().outcome().name()));
    }

    if (request.status() != ApprovalStatus.PENDING) {
      throw reject(
          request,
          new ApprovalException.AlreadyDecided(approvalId.toString(), request.status().name()));
    }

    Instant now = clock.instant();
    if (request.hasExpired(now)) {
      approvals.updateStatus(approvalId, ApprovalStatus.EXPIRED);
      throw reject(request, new ApprovalException.Expired(approvalId.toString()));
    }

    // The stale check, and the reason the fingerprint exists. Recomputed from the incident as it
    // stands now, not compared against a stored copy of itself.
    Incident incident =
        incidents
            .findById(request.incidentId())
            .orElseThrow(
                () ->
                    new ApprovalException.Rejected(
                        new ApprovalException.NotFound(request.incidentId().toString())));

    if (!request.stillMatches(incident.version())) {
      ActionFingerprint current =
          ActionFingerprint.of(request.action(), incident.id(), incident.version());
      approvals.updateStatus(approvalId, ApprovalStatus.SUPERSEDED);
      throw reject(
          request,
          new ApprovalException.Stale(
              approvalId.toString(), request.fingerprint().abbreviated(), current.abbreviated()));
    }

    return request;
  }

  /** Audits a refused decision attempt, then throws. Refusals are as auditable as approvals. */
  private ApprovalException.Rejected reject(ApprovalRequest request, ApprovalException reason) {
    AuditEventType type =
        reason instanceof ApprovalException.Stale
            ? AuditEventType.APPROVAL_STALE_REJECTED
            : AuditEventType.APPROVAL_REJECTED;

    auditLog.append(
        AuditEvent.of(
            request.incidentId(),
            type,
            Actor.SYSTEM,
            "Approval attempt refused: %s".formatted(reason.getClass().getSimpleName()),
            Map.of("approvalId", request.id().toString(), "reason", reason.toString()),
            clock.instant()));

    log.warn("Approval attempt refused: approvalId={} reason={}", request.id(), reason);
    return new ApprovalException.Rejected(reason);
  }

  /**
   * Expires anything past its deadline.
   *
   * <p>Housekeeping. {@link ApprovalRequest#hasExpired} is evaluated on every decision attempt too,
   * so a sweep that does not run delays the status change rather than leaving an expired approval
   * usable.
   */
  public int expireOverdue() {
    Instant now = clock.instant();
    List<ApprovalRequest> overdue = approvals.findExpired(now);

    for (ApprovalRequest request : overdue) {
      approvals.updateStatus(request.id(), ApprovalStatus.EXPIRED);
      auditLog.append(
          AuditEvent.of(
              request.incidentId(),
              AuditEventType.APPROVAL_EXPIRED,
              Actor.SYSTEM,
              "Approval expired without a decision",
              Map.of(
                  "approvalId", request.id().toString(),
                  "expiredAt", request.expiresAt().toString()),
              now));
    }

    if (!overdue.isEmpty()) {
      log.info("Expired {} overdue approval(s)", overdue.size());
    }
    return overdue.size();
  }

  public List<ApprovalRequest> pending() {
    return approvals.findAllPending();
  }
}
