package com.lapczynski.commander.application;

import com.lapczynski.commander.application.port.ActorDirectory;
import com.lapczynski.commander.application.port.AlertSignal;
import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.IncidentWorkflow;
import com.lapczynski.commander.application.port.IncidentWorkflow.WorkflowResult;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives an incident through its lifecycle.
 *
 * <p>This is the class that decides what happens next, and it decides by reading the incident's
 * status out of the database. Nothing is held in memory between stages: an investigation that
 * pauses for a human leaves a row, and the process that resumes it may be a different process
 * entirely. The agent runtime is reached through {@link IncidentWorkflow}, so every rule about what
 * may happen to an incident lives here, in code with no framework in it, rather than in a prompt.
 *
 * <p><strong>The version dance is the subtle part.</strong> An approval binds to the incident's
 * version (ADR-0007), and the runtime has to be told which version the proposal is bound to
 * <em>before</em> it emits the confirmable tool call — at which point the incident has not yet
 * moved to {@code AWAITING_APPROVAL}. So the version the incident is about to hold is computed,
 * passed in, and then checked against reality once the transition happens. If those ever disagree
 * the approval is not created and the incident fails: an approval bound to a version the incident
 * does not hold is one that {@code ApprovalService} would refuse later anyway, and failing here
 * says why.
 */
public class IncidentService {

  private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

  /**
   * How long an approval request stays usable.
   *
   * <p>An hour. Long enough for someone to be fetched, short enough that an approval cannot be
   * redeemed against a system that has moved on. An approval with no deadline is one that can be
   * used months later by someone who no longer remembers the incident.
   */
  public static final Duration DEFAULT_APPROVAL_TTL = Duration.ofHours(1);

  private final IncidentRepository incidents;
  private final ApprovalRepository approvals;
  private final ActorDirectory actors;
  private final ApprovalService approvalService;
  private final IncidentWorkflow workflow;
  private final IncidentReportService reports;
  private final AuditLog auditLog;
  private final Clock clock;
  private final Duration approvalTtl;

  public IncidentService(
      IncidentRepository incidents,
      ApprovalRepository approvals,
      ActorDirectory actors,
      ApprovalService approvalService,
      IncidentWorkflow workflow,
      IncidentReportService reports,
      AuditLog auditLog,
      Clock clock) {
    this(
        incidents,
        approvals,
        actors,
        approvalService,
        workflow,
        reports,
        auditLog,
        clock,
        DEFAULT_APPROVAL_TTL);
  }

  public IncidentService(
      IncidentRepository incidents,
      ApprovalRepository approvals,
      ActorDirectory actors,
      ApprovalService approvalService,
      IncidentWorkflow workflow,
      IncidentReportService reports,
      AuditLog auditLog,
      Clock clock,
      Duration approvalTtl) {
    this.incidents = incidents;
    this.approvals = approvals;
    this.actors = actors;
    this.approvalService = approvalService;
    this.workflow = workflow;
    this.reports = reports;
    this.auditLog = auditLog;
    this.clock = clock;
    this.approvalTtl = approvalTtl;
  }

  // -------------------------------------------------------------------------------------------
  // Intake
  // -------------------------------------------------------------------------------------------

  /**
   * Records an alert as an incident. Investigates nothing.
   *
   * <p>Separate from {@link #investigate} on purpose. An alert that arrives is a fact worth storing
   * whether or not anything is able to look at it, and an intake path that also started an agent
   * would lose the alert whenever the agent could not run.
   */
  public Incident open(AlertSignal alert, Actor reporter) {
    actors.record(reporter);

    Incident incident =
        Incident.open(
            IncidentId.newId(),
            alert.title(),
            new ServiceRef(alert.serviceName(), alert.environment()),
            alert.severity(),
            clock.instant());

    incidents.create(incident, reporter);

    auditLog.append(
        AuditEvent.of(
            incident.id(),
            AuditEventType.INCIDENT_OPENED,
            reporter,
            "Incident opened from an alert on %s".formatted(alert.serviceName()),
            Map.of(
                "service", alert.serviceName(),
                "environment", alert.environment(),
                "severity", alert.severity().name(),
                "metric", alert.metricName(),
                "measurable", String.valueOf(alert.isMeasurable())),
            clock.instant()));

    log.info(
        "Incident opened: incidentId={} service={} severity={} measurable={}",
        incident.id(),
        alert.serviceName(),
        alert.severity(),
        alert.isMeasurable());

    return incident;
  }

  // -------------------------------------------------------------------------------------------
  // Investigation
  // -------------------------------------------------------------------------------------------

  /**
   * Runs the investigation to its first stopping point.
   *
   * <p>Returns with the incident either awaiting a human, resolved with nothing to do, or failed.
   * It never returns with the incident mid-flight: a status that nothing will move on from is worse
   * than a recorded failure, because it looks like progress.
   */
  public Incident investigate(IncidentId incidentId, AlertSignal alert) {
    Incident incident = require(incidentId);

    if (incident.status() != IncidentStatus.RECEIVED) {
      throw new IllegalStateException(
          "incident %s is %s; only a RECEIVED incident can be investigated"
              .formatted(incidentId, incident.status()));
    }

    incident = advance(incident, IncidentStatus.INVESTIGATING, Actor.SYSTEM, "Investigation began");

    // The version the incident will hold once it reaches AWAITING_APPROVAL: the two transitions
    // below, plus the one that happens after the run. Computed rather than read, because the
    // runtime needs it before it emits the tool call, and checked after the fact below.
    long approvalVersion = incident.version() + 3;

    WorkflowResult result;
    try {
      result = workflow.investigate(incident, alert, approvalVersion);
    } catch (RuntimeException e) {
      log.error("Investigation failed: incidentId={}", incidentId, e);
      return fail(incident, "The investigation could not complete: " + e.getMessage());
    }

    incident =
        advance(
            incident,
            IncidentStatus.FORMING_HYPOTHESIS,
            Actor.SYSTEM,
            "Evidence collected; hypothesis formed");

    return switch (result) {
      case WorkflowResult.AwaitingApproval awaiting -> {
        Incident planning =
            advance(
                incident,
                IncidentStatus.PLANNING_REMEDIATION,
                Actor.SYSTEM,
                "Remediation proposed and allowed by policy");
        yield awaitApproval(planning, awaiting, approvalVersion);
      }

      case WorkflowResult.NoActionProposed noAction ->
          resolve(incident, "No action needed. " + noAction.summary());

      case WorkflowResult.Refused refused -> {
        // The gate saying no is the system working. The incident is closed as resolved-without-
        // action rather than failed, and the explanation is recorded so a reader can disagree.
        Incident planning =
            advance(
                incident,
                IncidentStatus.PLANNING_REMEDIATION,
                Actor.SYSTEM,
                "Remediation proposed");
        yield resolve(
            planning, "Policy refused the proposed remediation: " + refused.explanation());
      }

      case WorkflowResult.Failed failed -> fail(incident, failed.reason());

      // Neither can be produced by this stage: execution happens only after a human decides, and
      // verification only after something executed.
      case WorkflowResult.Executed ignored ->
          fail(
              incident,
              "The investigation executed an action without asking. Refusing to "
                  + "continue; this is a defect, not a configuration problem.");
      case WorkflowResult.Verified ignored ->
          fail(incident, "The investigation verified recovery before anything was remediated.");
    };
  }

  /**
   * Moves to {@code AWAITING_APPROVAL} and records the request a human will answer.
   *
   * <p>The order matters: the incident is moved first, so that the version the approval binds to is
   * the version the incident actually holds while waiting. Creating the request first and moving
   * afterwards would produce an approval that is stale the instant it exists.
   */
  private Incident awaitApproval(
      Incident incident, WorkflowResult.AwaitingApproval awaiting, long approvalVersion) {

    Incident waiting =
        advance(
            incident,
            IncidentStatus.AWAITING_APPROVAL,
            Actor.SYSTEM,
            "Waiting for a human to authorise %s".formatted(awaiting.action().type()));

    if (waiting.version() != approvalVersion) {
      // The runtime was told a version that turned out to be wrong, so the fingerprint the
      // executor will compute cannot match the one this approval would carry. Failing here is the
      // only honest outcome: the alternative is an approval that ApprovalService refuses as stale
      // at the moment a human finally looks at it.
      log.error(
          "Version drift between the runtime and the incident: expected={} actual={} incidentId={}",
          approvalVersion,
          waiting.version(),
          waiting.id());
      return fail(
          waiting,
          "Internal consistency check failed: the proposal was bound to incident version %d but "
                  .formatted(approvalVersion)
              + "the incident is at version %d. No approval was created."
                  .formatted(waiting.version()));
    }

    Instant now = clock.instant();
    ApprovalRequest request =
        ApprovalRequest.pending(
            ApprovalId.newId(),
            waiting.id(),
            waiting.version(),
            awaiting.action(),
            awaiting.risk(),
            awaiting.rationale(),
            awaiting.expectedImpact(),
            awaiting.supportingEvidence(),
            now,
            now.plus(approvalTtl),
            awaiting.confirmationCallId());

    approvalService.request(request);
    return waiting;
  }

  // -------------------------------------------------------------------------------------------
  // The human decision
  // -------------------------------------------------------------------------------------------

  /**
   * Approves a request and carries the incident through to a verdict.
   *
   * <p>{@link ApprovalService#approve} does the authorising and throws if the decision cannot be
   * accepted. Everything after that point is consequence: the incident moves, the paused invocation
   * is resumed, and recovery is measured. Nothing here re-decides whether the action was allowed —
   * the tool does that again for itself, because the world moves between a decision and its
   * execution.
   */
  public Incident approve(ApprovalId approvalId, Actor approver, String comment) {
    // Recorded before the decision is attempted, and before it is known whether the decision will
    // be accepted. Every table that stores who did something references this row, including the
    // audit event written when an approval is *refused* — so registering only on success would
    // make exactly the interesting refusals unwritable.
    actors.record(approver);

    ApprovalRequest request = requireApproval(approvalId);
    ActionFingerprint authorised = approvalService.approve(approvalId, approver, comment);

    Incident incident = require(request.incidentId());
    incident =
        advance(
            incident,
            IncidentStatus.REMEDIATING,
            approver,
            "%s authorised %s".formatted(approver.displayName(), request.action().type()));

    WorkflowResult result;
    try {
      result =
          workflow.resume(
              incident,
              request
                  .adkFunctionCallId()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "approval %s has no recorded confirmation call; the paused "
                                      .formatted(approvalId)
                                  + "invocation cannot be identified")),
              true);
    } catch (RuntimeException e) {
      log.error("Remediation failed: incidentId={} fingerprint={}", incident.id(), authorised, e);
      return fail(incident, "The approved action could not be executed: " + e.getMessage());
    }

    return switch (result) {
      case WorkflowResult.Executed executed -> verify(incident, executed);

      // Policy refused at execution time. Approval is necessary and never sufficient, and this is
      // what that sentence looks like when it happens.
      case WorkflowResult.Refused refused ->
          fail(incident, "Refused at execution time: " + refused.explanation());
      case WorkflowResult.Failed failed -> fail(incident, failed.reason());

      case WorkflowResult.AwaitingApproval ignored ->
          fail(incident, "The runtime asked for approval again after one was granted.");
      case WorkflowResult.NoActionProposed ignored ->
          fail(incident, "The approved action vanished between approval and execution.");
      case WorkflowResult.Verified ignored ->
          fail(incident, "Recovery was verified before the action ran.");
    };
  }

  /** Records a rejection and closes the incident. */
  public Incident reject(ApprovalId approvalId, Actor approver, String comment) {
    actors.record(approver);

    ApprovalRequest request = requireApproval(approvalId);
    approvalService.reject(approvalId, approver, comment);

    Incident incident = require(request.incidentId());

    // Best effort, and deliberately not fatal. The decision is already recorded and the incident
    // is already closing; failing the rejection because the agent could not be told would leave a
    // human's "no" looking like an error.
    request
        .adkFunctionCallId()
        .ifPresent(
            callId -> {
              try {
                workflow.resume(incident, callId, false);
              } catch (RuntimeException e) {
                log.warn(
                    "Could not deliver the rejection to the paused invocation: incidentId={}",
                    incident.id(),
                    e);
              }
            });

    return close(
        incident,
        IncidentStatus.REJECTED,
        approver,
        "%s declined the proposed remediation".formatted(approver.displayName()));
  }

  // -------------------------------------------------------------------------------------------
  // Verification and closure
  // -------------------------------------------------------------------------------------------

  private Incident verify(Incident incident, WorkflowResult.Executed executed) {
    Incident verifying =
        advance(
            incident,
            IncidentStatus.VERIFYING,
            Actor.SYSTEM,
            executed.dryRun()
                ? "Action simulated; measuring the symptom anyway"
                : "Action executed; measuring whether the symptom went away");

    AlertSignal alert = alertFor(verifying);

    WorkflowResult result;
    try {
      result = workflow.verify(verifying, alert);
    } catch (RuntimeException e) {
      log.error("Verification failed: incidentId={}", verifying.id(), e);
      return fail(verifying, "Recovery could not be measured: " + e.getMessage());
    }

    if (!(result instanceof WorkflowResult.Verified verified)) {
      return fail(
          verifying,
          result instanceof WorkflowResult.Failed failed
              ? failed.reason()
              : "Verification produced no verdict.");
    }

    reports.generate(verifying.id(), verified.narrative());

    // Only RECOVERED resolves. Every other outcome — including "the metric could not be read" —
    // fails the incident and asks for a human, because treating an unmeasurable result as success
    // is the single most dangerous defaulting mistake available here (ADR-0010).
    return verified.resolved()
        ? close(verifying, IncidentStatus.RESOLVED, Actor.SYSTEM, verified.summary())
        : fail(verifying, verified.summary());
  }

  /** Cancels an incident that is still in flight. */
  public Incident cancel(IncidentId incidentId, Actor actor, String reason) {
    actors.record(actor);

    Incident incident = require(incidentId);
    return close(
        incident,
        IncidentStatus.CANCELLED,
        actor,
        reason == null || reason.isBlank() ? "Cancelled by an operator" : reason);
  }

  // -------------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------------

  public Optional<Incident> find(IncidentId incidentId) {
    return incidents.findById(incidentId);
  }

  public List<Incident> openIncidents(int limit) {
    return incidents.findOpen(limit);
  }

  public List<ApprovalRequest> pendingApprovals() {
    return approvalService.pending();
  }

  public Optional<ApprovalRequest> pendingApprovalFor(IncidentId incidentId) {
    return approvals.findPendingForIncident(incidentId);
  }

  // -------------------------------------------------------------------------------------------
  // Transitions
  // -------------------------------------------------------------------------------------------

  private Incident advance(Incident incident, IncidentStatus target, Actor actor, String reason) {
    Incident moved = incident.transitionTo(target, clock.instant());
    incidents.update(moved, incident.version(), actor, reason);
    return moved;
  }

  private Incident close(Incident incident, IncidentStatus target, Actor actor, String note) {
    Incident closed = incident.close(target, note, clock.instant());
    incidents.update(closed, incident.version(), actor, note);

    log.info("Incident closed: incidentId={} status={}", closed.id(), target);
    return closed;
  }

  private Incident resolve(Incident incident, String note) {
    return close(incident, IncidentStatus.RESOLVED, Actor.SYSTEM, note);
  }

  /**
   * Records a failure without losing the reason.
   *
   * <p>Never throws. By the time this is reached something has already gone wrong, and an exception
   * here would replace a recorded failure with an unrecorded one.
   */
  private Incident fail(Incident incident, String reason) {
    try {
      return close(incident, IncidentStatus.FAILED, Actor.SYSTEM, reason);
    } catch (RuntimeException e) {
      log.error("Could not record the failure of incident {}: {}", incident.id(), reason, e);
      return incident;
    }
  }

  private Incident require(IncidentId incidentId) {
    return incidents
        .findById(incidentId)
        .orElseThrow(() -> new NoSuchElementException("no incident " + incidentId));
  }

  private ApprovalRequest requireApproval(ApprovalId approvalId) {
    return approvals
        .findById(approvalId)
        .orElseThrow(() -> new NoSuchElementException("no approval request " + approvalId));
  }

  /**
   * Reconstructs the verification criteria from the incident.
   *
   * <p>The measurable part of the original alert is carried in session state by the runtime, so
   * this supplies only what identifies the service. Rebuilding thresholds here from anything the
   * investigation produced would let the model choose the bar it is measured against.
   */
  private AlertSignal alertFor(Incident incident) {
    return new AlertSignal(
        incident.title(),
        incident.affectedService().name(),
        incident.affectedService().environment(),
        incident.severity(),
        "",
        Double.NaN,
        Double.NaN,
        "");
  }
}
