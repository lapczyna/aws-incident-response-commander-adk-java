package com.lapczynski.commander.application.report;

import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.ReportRepository;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.report.IncidentReport;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the incident report from stored rows and a model-written narrative.
 *
 * <p>Every input is read back from the database rather than carried in memory from the
 * investigation. That is slower and it is the point: it means the report describes what was durably
 * recorded, so a claim in the report and a row in the audit log cannot disagree. It also means a
 * report can be regenerated for an incident this process never handled.
 *
 * <p>The narrative is the only model contribution, and it is the only part that is checked rather
 * than trusted — see {@link IncidentReportRenderer}.
 */
public class IncidentReportService {

  private static final Logger log = LoggerFactory.getLogger(IncidentReportService.class);

  private final IncidentRepository incidents;
  private final EvidenceRepository evidence;
  private final AuditLog auditLog;
  private final ApprovalRepository approvals;
  private final ExecutionRepository executions;
  private final VerificationRepository verifications;
  private final ReportRepository reports;
  private final IncidentReportRenderer renderer;
  private final Clock clock;

  public IncidentReportService(
      IncidentRepository incidents,
      EvidenceRepository evidence,
      AuditLog auditLog,
      ApprovalRepository approvals,
      ExecutionRepository executions,
      VerificationRepository verifications,
      ReportRepository reports,
      IncidentReportRenderer renderer,
      Clock clock) {
    this.incidents = incidents;
    this.evidence = evidence;
    this.auditLog = auditLog;
    this.approvals = approvals;
    this.executions = executions;
    this.verifications = verifications;
    this.reports = reports;
    this.renderer = renderer;
    this.clock = clock;
  }

  /**
   * Generates and stores the report.
   *
   * @param narrative the model's prose. A blank narrative produces a report that says so rather
   *     than failing: a report with a complete timeline and no story is still worth having, and
   *     refusing to write one because the model was unavailable would lose the evidence too.
   */
  public IncidentReport generate(IncidentId incidentId, String narrative) {
    Incident incident =
        incidents
            .findById(incidentId)
            .orElseThrow(() -> new IllegalArgumentException("no such incident: " + incidentId));

    List<Evidence> collected = evidence.findByIncident(incidentId);
    Optional<ApprovalRequest> approval = approvals.findPendingForIncident(incidentId);

    // A decided approval is no longer pending, so the pending lookup misses exactly the case a
    // finished report is about. Fall back to the executed action, which names the approval that
    // authorised it.
    Optional<ExecutedAction> execution = executions.findLatestForIncident(incidentId);
    if (approval.isEmpty()) {
      approval = execution.flatMap(ExecutedAction::approvalId).flatMap(approvals::findById);
    }

    Optional<ApprovalDecision> decision =
        approval.flatMap(request -> approvals.findDecision(request.id()));

    IncidentReportRenderer.ReportInputs inputs =
        new IncidentReportRenderer.ReportInputs(
            incident,
            collected,
            evidence.findGapsByIncident(incidentId),
            auditLog.findByIncident(incidentId),
            approval,
            decision,
            execution.flatMap(ExecutedAction::detail),
            execution.map(ExecutedAction::dryRun).orElse(false),
            verifications.findByIncident(incidentId));

    String markdown = renderer.render(inputs, narrative);

    IncidentReport report =
        IncidentReport.of(
            incidentId, markdown, collected.stream().map(Evidence::id).toList(), clock.instant());

    reports.save(report);

    log.info(
        "Incident report generated: incident={} evidence={} gaps={} chars={}",
        incidentId,
        collected.size(),
        inputs.gaps().size(),
        markdown.length());

    return report;
  }

  /** The most recently generated report, if one exists. */
  public Optional<IncidentReport> latest(IncidentId incidentId) {
    return reports.findLatest(incidentId);
  }

  /**
   * The evidence a narrative may cite, rendered for a prompt.
   *
   * <p>Supplied to the model with the citation numbers <em>already assigned</em>, so the model is
   * choosing which observation to reference rather than inventing a numbering scheme. It still gets
   * them wrong sometimes, which is why the renderer checks.
   */
  public String citableEvidence(IncidentId incidentId) {
    List<Evidence> collected = evidence.findByIncident(incidentId);
    if (collected.isEmpty()) {
      return "No evidence was collected for this incident.";
    }

    StringBuilder catalogue = new StringBuilder(1024);
    for (int index = 0; index < collected.size(); index++) {
      Evidence item = collected.get(index);
      catalogue
          .append("[E")
          .append(index + 1)
          .append("] ")
          .append(item.source())
          .append(" — ")
          .append(item.summary())
          .append('\n');
    }
    return catalogue.toString();
  }

  /** The ids behind the citation numbers handed to the model, in the same order. */
  public List<EvidenceId> citationOrder(IncidentId incidentId) {
    return evidence.findByIncident(incidentId).stream().map(Evidence::id).toList();
  }
}
