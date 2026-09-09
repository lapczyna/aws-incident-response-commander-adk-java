package com.lapczynski.commander.application.report;

import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the incident report.
 *
 * <p><strong>The model writes the narrative; this class writes everything else.</strong> That
 * division is the whole point, and it is what makes "every conclusion references stored evidence" a
 * property rather than an aspiration.
 *
 * <p>A model asked to produce a report with citations will produce something that <em>looks</em>
 * cited. It will occasionally reference an observation it never read, renumber things, or drop a
 * citation when the conclusion feels obvious. None of that is visible in the prose. So the
 * timeline, the evidence table, the approval record and the verification result are all generated
 * here from stored rows, and the model's contribution is confined to the sections where being wrong
 * is visible as an opinion rather than as a fact.
 *
 * <p>The narrative is also <em>checked</em> rather than trusted: {@link #uncitedClaims} reports any
 * evidence reference in the model's text that does not correspond to a stored observation, and the
 * renderer puts that discrepancy in the report itself rather than hiding it.
 */
public class IncidentReportRenderer {

  /** Evidence references in the narrative look like {@code [E3]}. */
  private static final java.util.regex.Pattern CITATION =
      java.util.regex.Pattern.compile("\\[E(\\d+)\\]");

  /**
   * Renders the report.
   *
   * @param narrative the model's prose: what happened, why, and what was learned
   */
  public String render(ReportInputs inputs, String narrative) {
    StringBuilder report = new StringBuilder(4096);

    Incident incident = inputs.incident();
    Map<EvidenceId, Integer> citationNumbers = numberEvidence(inputs.evidence());

    report.append("# Incident report: ").append(incident.title()).append("\n\n");

    appendSummaryTable(report, inputs);
    appendNarrative(report, narrative, inputs, citationNumbers);
    appendTimeline(report, inputs);
    appendEvidence(report, inputs, citationNumbers);
    appendGaps(report, inputs);
    appendRemediation(report, inputs);
    appendVerification(report, inputs, citationNumbers);
    appendFooter(report);

    return report.toString();
  }

  // ------------------------------------------------------------------ parts

  private void appendSummaryTable(StringBuilder report, ReportInputs inputs) {
    Incident incident = inputs.incident();

    report.append("| | |\n|---|---|\n");
    report.append("| **Status** | ").append(incident.status()).append(" |\n");
    report.append("| **Service** | ").append(incident.affectedService()).append(" |\n");
    report.append("| **Severity** | ").append(incident.severity()).append(" |\n");
    report.append("| **Detected** | ").append(incident.receivedAt()).append(" |\n");
    report
        .append("| **Duration** | ")
        .append(humanDuration(Duration.between(incident.receivedAt(), incident.updatedAt())))
        .append(" |\n");

    inputs
        .verification()
        .ifPresent(
            verification ->
                report.append("| **Recovery** | ").append(verification.outcome()).append(" |\n"));
    report.append('\n');

    // Surfaced at the top, not buried. A report whose fix did not work is the one most likely to
    // be skim-read by someone assuming it succeeded.
    inputs
        .verification()
        .filter(RecoveryVerification::requiresHumanAttention)
        .ifPresent(
            verification ->
                report
                    .append("> **This incident is not resolved.** ")
                    .append(verification.summary())
                    .append("\n\n"));
  }

  private void appendNarrative(
      StringBuilder report,
      String narrative,
      ReportInputs inputs,
      Map<EvidenceId, Integer> citationNumbers) {

    // "Analysis" rather than "What happened": the narrator writes its own `###` headings,
    // one of which is "What happened", and two identical headings a line apart read as a
    // formatting bug rather than as structure.
    report.append("## Analysis\n\n");
    report.append(
        narrative == null || narrative.isBlank()
            ? "_No narrative was produced._"
            : narrative.trim());
    report.append("\n\n");

    Set<String> uncited = uncitedClaims(narrative, citationNumbers.size());
    if (!uncited.isEmpty()) {
      // Reported rather than silently stripped. A citation pointing at nothing is a finding about
      // the investigation, and hiding it would defeat the purpose of checking.
      report.append("> **Citation warning.** The narrative references ");
      report.append(String.join(", ", uncited));
      report.append(
          ", which do not correspond to any stored observation. Treat the associated claims as "
              + "unsupported.\n\n");
    }
  }

  /**
   * Builds the timeline from the audit log.
   *
   * <p>From the audit log specifically, not from the model's account of events. The audit log is
   * append-only and enforced by the database, so this section cannot disagree with what actually
   * happened.
   */
  private void appendTimeline(StringBuilder report, ReportInputs inputs) {
    report.append("## Timeline\n\n");

    if (inputs.auditTrail().isEmpty()) {
      report.append("_No timeline entries were recorded._\n\n");
      return;
    }

    report.append("| Time | Actor | Event |\n|---|---|---|\n");
    for (AuditEvent event : inputs.auditTrail()) {
      report
          .append("| ")
          .append(event.occurredAt())
          .append(" | ")
          .append(escape(event.actor().displayName()))
          .append(" | ")
          .append(escape(event.summary()))
          .append(" |\n");
    }
    report.append('\n');
  }

  private void appendEvidence(
      StringBuilder report, ReportInputs inputs, Map<EvidenceId, Integer> citationNumbers) {

    report.append("## Evidence\n\n");

    if (inputs.evidence().isEmpty()) {
      report.append("_No evidence was collected._\n\n");
      return;
    }

    report.append("| Ref | Source | Collected by | Observation |\n|---|---|---|---|\n");
    for (Evidence evidence : inputs.evidence()) {
      report
          .append("| E")
          .append(citationNumbers.get(evidence.id()))
          .append(" | ")
          .append(evidence.source())
          .append(evidence.isUntrusted() ? " ⚠︎" : "")
          .append(" | ")
          .append(escape(evidence.collectedBy()))
          .append(" | ")
          .append(escape(evidence.summary()))
          .append(" |\n");
    }

    boolean anyUntrusted = inputs.evidence().stream().anyMatch(Evidence::isUntrusted);
    if (anyUntrusted) {
      report.append(
          "\n⚠︎ marks evidence from a source that can carry text influenced by a caller — log "
              + "output and change descriptions. It was treated as data throughout, never as "
              + "instruction.\n");
    }
    report.append('\n');
  }

  /**
   * Lists evidence that could not be collected.
   *
   * <p>Its own section rather than a footnote. The difference between "the logs showed nothing" and
   * "the logs could not be read" changes what every conclusion above is worth, and a reader
   * skimming for the cause will not find that distinction if it is buried in prose.
   */
  private void appendGaps(StringBuilder report, ReportInputs inputs) {
    if (inputs.gaps().isEmpty()) {
      return;
    }

    report.append("## Missing evidence\n\n");
    report.append(
        "The following could not be collected. Conclusions above were reached without it.\n\n");
    report.append("| Source | Reason | Detail |\n|---|---|---|\n");

    for (EvidenceGap gap : inputs.gaps()) {
      report
          .append("| ")
          .append(gap.source())
          .append(" | ")
          .append(gap.reason())
          .append(" | ")
          .append(escape(gap.detail()))
          .append(" |\n");
    }
    report.append('\n');
  }

  private void appendRemediation(StringBuilder report, ReportInputs inputs) {
    report.append("## Remediation\n\n");

    Optional<ApprovalRequest> approval = inputs.approval();
    if (approval.isEmpty()) {
      report.append(
          "No remediation was proposed. The investigation concluded without an action to take.\n\n");
      return;
    }

    ApprovalRequest request = approval.get();
    report
        .append("**Proposed:** ")
        .append(escape(request.action().humanDescription()))
        .append("\n\n");
    report.append("| | |\n|---|---|\n");
    report.append("| **Action** | `").append(request.action().type()).append("` |\n");
    report.append("| **Target** | `").append(request.action().target().arn()).append("` |\n");
    report.append("| **Risk** | ").append(request.risk()).append(" |\n");
    report
        .append("| **Expected impact** | ")
        .append(escape(request.expectedImpact()))
        .append(" |\n");
    // The fingerprint is included so a reader can tie the approval to the executed action without
    // trusting that the two sections describe the same thing.
    report
        .append("| **Fingerprint** | `")
        .append(request.fingerprint().abbreviated())
        .append("` |\n");

    inputs
        .decision()
        .ifPresent(
            decision -> {
              report
                  .append("| **Decision** | ")
                  .append(decision.outcome())
                  .append(" by ")
                  .append(escape(decision.decidedBy().displayName()))
                  .append(" |\n");
              decision
                  .comment()
                  .ifPresent(
                      comment ->
                          report.append("| **Comment** | ").append(escape(comment)).append(" |\n"));
            });

    if (inputs.decision().isEmpty()) {
      report.append("| **Decision** | none recorded |\n");
    }

    report.append('\n');

    inputs
        .executionDetail()
        .ifPresent(
            detail -> {
              report.append("**Execution:** ").append(escape(detail)).append("\n\n");
              if (inputs.wasDryRun()) {
                // Stated unmissably. A reader must never come away believing a real change was
                // made when it was not.
                report.append(
                    "> **Dry run.** The action was simulated. Nothing in AWS was changed.\n\n");
              }
            });
  }

  private void appendVerification(
      StringBuilder report, ReportInputs inputs, Map<EvidenceId, Integer> citationNumbers) {

    Optional<RecoveryVerification> verification = inputs.verification();
    if (verification.isEmpty()) {
      return;
    }

    RecoveryVerification result = verification.get();
    report.append("## Recovery verification\n\n");
    report.append("| | |\n|---|---|\n");
    report.append("| **Outcome** | **").append(result.outcome()).append("** |\n");
    report.append("| **Metric** | `").append(result.metricName()).append("` |\n");
    report.append("| **Before** | ").append(format(result.beforeValue())).append(" |\n");
    report.append("| **After** | ").append(format(result.afterValue())).append(" |\n");
    report
        .append("| **Recovery threshold** | ")
        .append(format(result.recoveryThreshold()))
        .append(" |\n\n");

    report.append(escape(result.summary())).append("\n\n");

    String refs =
        result.supportingEvidence().stream()
            .map(citationNumbers::get)
            .filter(java.util.Objects::nonNull)
            .map(number -> "E" + number)
            .collect(Collectors.joining(", "));
    if (!refs.isEmpty()) {
      report.append("Based on ").append(refs).append(".\n\n");
    }
  }

  private void appendFooter(StringBuilder report) {
    report.append("---\n\n");
    report.append(
        "_Generated by the AWS Incident Response Commander. The timeline, evidence table, "
            + "approval record and verification result are assembled from stored rows; only the "
            + "narrative section is model-written._\n");
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Assigns stable citation numbers in collection order.
   *
   * <p>Assigned here rather than by the model, so a reference in the narrative can be checked
   * against a number the model did not choose.
   */
  private Map<EvidenceId, Integer> numberEvidence(List<Evidence> evidence) {
    return java.util.stream.IntStream.range(0, evidence.size())
        .boxed()
        .collect(
            Collectors.toMap(
                index -> evidence.get(index).id(),
                index -> index + 1,
                (a, b) -> a,
                java.util.LinkedHashMap::new));
  }

  /**
   * Finds citations in the narrative that point at nothing.
   *
   * <p>This is the check that turns the citation requirement into a verifiable property. A
   * reference to {@code [E7]} when only six observations exist means the model invented support for
   * a claim, and the report says so.
   */
  Set<String> uncitedClaims(String narrative, int evidenceCount) {
    if (narrative == null || narrative.isBlank()) {
      return Set.of();
    }

    Set<String> invalid = new LinkedHashSet<>();
    var matcher = CITATION.matcher(narrative);
    while (matcher.find()) {
      int referenced = Integer.parseInt(matcher.group(1));
      if (referenced < 1 || referenced > evidenceCount) {
        invalid.add(matcher.group());
      }
    }
    return invalid;
  }

  /** Escapes pipe characters so evidence text cannot break out of a Markdown table cell. */
  private static String escape(String text) {
    return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
  }

  private static String format(double value) {
    return Double.isNaN(value)
        ? "not measured"
        : String.valueOf(Math.round(value * 1000.0) / 1000.0);
  }

  private static String humanDuration(Duration duration) {
    long minutes = duration.toMinutes();
    if (minutes < 60) {
      return minutes + " min";
    }
    return "%dh %dm".formatted(minutes / 60, minutes % 60);
  }

  /** Everything the report is built from. All of it comes from storage. */
  public record ReportInputs(
      Incident incident,
      List<Evidence> evidence,
      List<EvidenceGap> gaps,
      List<AuditEvent> auditTrail,
      Optional<ApprovalRequest> approval,
      Optional<ApprovalDecision> decision,
      Optional<String> executionDetail,
      boolean wasDryRun,
      Optional<RecoveryVerification> verification) {

    public ReportInputs {
      evidence = List.copyOf(evidence);
      gaps = List.copyOf(gaps);
      auditTrail = List.copyOf(auditTrail);
    }

    /** Inputs for an investigation that produced no remediation. */
    public static ReportInputs investigationOnly(
        Incident incident,
        List<Evidence> evidence,
        List<EvidenceGap> gaps,
        List<AuditEvent> auditTrail) {
      return new ReportInputs(
          incident,
          evidence,
          gaps,
          auditTrail,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          false,
          Optional.empty());
    }
  }
}
