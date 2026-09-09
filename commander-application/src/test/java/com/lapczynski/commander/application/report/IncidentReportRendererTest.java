package com.lapczynski.commander.application.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Incident report rendering")
class IncidentReportRendererTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

  private final IncidentReportRenderer renderer = new IncidentReportRenderer();

  @Nested
  @DisplayName("citations")
  class Citations {

    @Test
    @DisplayName("a citation pointing at nothing is reported in the report itself")
    void invalidCitationIsReported() {
      // The whole reason the renderer assembles citations rather than trusting them. A model that
      // references [E7] when six observations exist has invented support for a claim, and the
      // reader must be told rather than shown a report that looks fully sourced.
      String report =
          renderer.render(
              inputs(2, List.of(), Optional.empty()),
              "Latency stepped up [E1] after a deployment [E7].");

      assertThat(report).contains("Citation warning");
      assertThat(report).contains("[E7]");
      assertThat(report).contains("unsupported");
    }

    @Test
    @DisplayName("valid citations produce no warning")
    void validCitationsAreQuiet() {
      String report =
          renderer.render(
              inputs(3, List.of(), Optional.empty()),
              "Latency stepped up [E1] alongside a deployment [E2] and errors rose [E3].");

      assertThat(report).doesNotContain("Citation warning");
    }

    @Test
    @DisplayName("citation zero is invalid, not merely out of order")
    void zeroIsInvalid() {
      String report = renderer.render(inputs(2, List.of(), Optional.empty()), "A claim [E0].");

      assertThat(report).contains("Citation warning").contains("[E0]");
    }

    @Test
    @DisplayName("citation numbers follow stored order, not the order the model used them")
    void numbersFollowStorage() {
      IncidentReportRenderer.ReportInputs inputs = inputs(3, List.of(), Optional.empty());

      String report = renderer.render(inputs, "Third first [E3], then first [E1].");

      // E1 must label the first stored observation regardless of where the prose mentions it.
      assertThat(report).contains("| E1 | CLOUDWATCH_METRICS");
      assertThat(report).contains("observation 1");
      assertThat(report).doesNotContain("Citation warning");
    }

    @Test
    @DisplayName("a missing narrative does not fail the report")
    void noNarrative() {
      // A report with a complete timeline and no story is still worth having. Refusing to write
      // one because the model was unavailable would lose the evidence too.
      String report = renderer.render(inputs(2, List.of(), Optional.empty()), "");

      assertThat(report).contains("No narrative was produced");
      assertThat(report).contains("## Timeline");
      assertThat(report).contains("## Evidence");
    }
  }

  @Nested
  @DisplayName("the parts a model does not write")
  class AssembledSections {

    @Test
    @DisplayName("the timeline comes from the audit log")
    void timelineFromAudit() {
      String report = renderer.render(inputs(1, List.of(), Optional.empty()), "Narrative [E1].");

      assertThat(report).contains("| Time | Actor | Event |");
      assertThat(report).contains("Incident opened from alarm");
      assertThat(report).contains("Human approved the rollback");
    }

    @Test
    @DisplayName("evidence from an untrusted source is marked as such")
    void untrustedEvidenceIsMarked() {
      Evidence logLine =
          new Evidence(
              EvidenceId.newId(),
              IncidentId.newId(),
              EvidenceSource.CLOUDWATCH_LOGS,
              "logs_investigator",
              "22 DNS resolution warnings",
              "WARN DNS resolution took 2840ms",
              Confidence.clamped(0.8),
              T0,
              Optional.empty());

      IncidentReportRenderer.ReportInputs inputs =
          new IncidentReportRenderer.ReportInputs(
              incident(IncidentStatus.RESOLVED),
              List.of(logLine),
              List.of(),
              auditTrail(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              false,
              Optional.empty());

      String report = renderer.render(inputs, "Narrative [E1].");

      assertThat(report).contains("⚠︎");
      assertThat(report).contains("never as instruction");
    }

    @Test
    @DisplayName("missing evidence gets its own section rather than a footnote")
    void gapsAreProminent() {
      // "The logs showed nothing" and "the logs could not be read" change what every conclusion
      // above them is worth, and a reader skimming for the cause will not find that distinction
      // buried in prose.
      EvidenceGap gap =
          new EvidenceGap(
              IncidentId.newId(),
              EvidenceSource.CLOUDTRAIL_CHANGES,
              "change_investigator",
              EvidenceGap.Reason.ACCESS_DENIED,
              "CloudTrail lookup was refused",
              T0);

      String report = renderer.render(inputs(2, List.of(gap), Optional.empty()), "Narrative [E1].");

      assertThat(report).contains("## Missing evidence");
      assertThat(report).contains("ACCESS_DENIED");
      assertThat(report).contains("Conclusions above were reached without it");
    }

    @Test
    @DisplayName("a pipe in evidence text cannot break the table")
    void pipesAreEscaped() {
      Evidence awkward =
          new Evidence(
              EvidenceId.newId(),
              IncidentId.newId(),
              EvidenceSource.CLOUDWATCH_LOGS,
              "logs_investigator",
              "Saw | a pipe | and more",
              "content",
              Confidence.clamped(0.5),
              T0,
              Optional.empty());

      IncidentReportRenderer.ReportInputs inputs =
          new IncidentReportRenderer.ReportInputs(
              incident(IncidentStatus.RESOLVED),
              List.of(awkward),
              List.of(),
              List.of(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              false,
              Optional.empty());

      String report = renderer.render(inputs, "Narrative [E1].");

      assertThat(report).contains("Saw \\| a pipe \\| and more");
    }
  }

  @Nested
  @DisplayName("remediation and verification")
  class Outcomes {

    @Test
    @DisplayName("a dry run says so unmissably")
    void dryRunIsUnmissable() {
      // A reader must never come away believing a real change was made when it was not.
      IncidentReportRenderer.ReportInputs inputs =
          new IncidentReportRenderer.ReportInputs(
              incident(IncidentStatus.RESOLVED),
              evidence(1),
              List.of(),
              auditTrail(),
              Optional.of(approval()),
              Optional.of(decision()),
              Optional.of("DRY RUN: ROLLBACK_DEPLOYMENT would have been performed."),
              true,
              Optional.empty());

      String report = renderer.render(inputs, "Narrative [E1].");

      assertThat(report).contains("**Dry run.**");
      assertThat(report).contains("Nothing in AWS was changed");
    }

    @Test
    @DisplayName("a real execution carries no dry-run banner")
    void realExecutionHasNoBanner() {
      IncidentReportRenderer.ReportInputs inputs =
          new IncidentReportRenderer.ReportInputs(
              incident(IncidentStatus.RESOLVED),
              evidence(1),
              List.of(),
              auditTrail(),
              Optional.of(approval()),
              Optional.of(decision()),
              Optional.of("Forced a new deployment of checkout."),
              false,
              Optional.empty());

      String report = renderer.render(inputs, "Narrative [E1].");

      assertThat(report).doesNotContain("**Dry run.**");
      assertThat(report).contains("Forced a new deployment of checkout.");
    }

    @Test
    @DisplayName("a failed verification is stated at the top, not buried")
    void failedVerificationIsAtTheTop() {
      // The report most likely to be skim-read by someone assuming success is exactly the one
      // where the fix did not work.
      RecoveryVerification failed =
          new RecoveryVerification(
              IncidentId.newId(),
              "TargetResponseTimeP99",
              1.15,
              1.14,
              0.4,
              RecoveryVerification.Outcome.NOT_RECOVERED,
              "Latency is still 1.14 against a recovery threshold of 0.4.",
              List.of(EvidenceId.newId()),
              T0);

      String report = renderer.render(inputs(1, List.of(), Optional.of(failed)), "Narrative [E1].");

      int bannerAt = report.indexOf("This incident is not resolved");
      int narrativeAt = report.indexOf("## Analysis");

      assertThat(bannerAt).isPositive();
      assertThat(bannerAt).isLessThan(narrativeAt);
      assertThat(report).contains("NOT_RECOVERED");
    }

    @Test
    @DisplayName("a successful verification carries no alarm banner")
    void successfulVerificationIsQuiet() {
      RecoveryVerification recovered =
          new RecoveryVerification(
              IncidentId.newId(),
              "TargetResponseTimeP99",
              1.15,
              0.19,
              0.4,
              RecoveryVerification.Outcome.RECOVERED,
              "Latency is back to 0.19.",
              List.of(EvidenceId.newId()),
              T0);

      String report =
          renderer.render(inputs(1, List.of(), Optional.of(recovered)), "Narrative [E1].");

      assertThat(report).doesNotContain("This incident is not resolved");
      assertThat(report).contains("**RECOVERED**");
    }

    @Test
    @DisplayName("an unmeasured value reads as not measured, never as zero")
    void nanIsNotZero() {
      // Printing NaN as 0.0 would show a latency of zero, which reads as spectacular recovery.
      RecoveryVerification indeterminate =
          new RecoveryVerification(
              IncidentId.newId(),
              "TargetResponseTimeP99",
              1.15,
              Double.NaN,
              0.4,
              RecoveryVerification.Outcome.INDETERMINATE,
              "The metric could not be read after remediation.",
              List.of(EvidenceId.newId()),
              T0);

      String report =
          renderer.render(inputs(1, List.of(), Optional.of(indeterminate)), "Narrative [E1].");

      assertThat(report).contains("not measured");
      assertThat(report).doesNotContain("| **After** | 0.0 |");
      assertThat(report).contains("This incident is not resolved");
    }

    @Test
    @DisplayName("an investigation with no proposal says so plainly")
    void noRemediation() {
      String report = renderer.render(inputs(1, List.of(), Optional.empty()), "Narrative [E1].");

      assertThat(report).contains("No remediation was proposed");
    }

    @Test
    @DisplayName("the approval section names the fingerprint the human authorised")
    void approvalCarriesFingerprint() {
      IncidentReportRenderer.ReportInputs inputs =
          new IncidentReportRenderer.ReportInputs(
              incident(IncidentStatus.RESOLVED),
              evidence(1),
              List.of(),
              auditTrail(),
              Optional.of(approval()),
              Optional.of(decision()),
              Optional.empty(),
              false,
              Optional.empty());

      String report = renderer.render(inputs, "Narrative [E1].");

      assertThat(report).contains(approval().fingerprint().abbreviated());
      assertThat(report).contains("APPROVED by Dana Approver");
      assertThat(report).contains("Rollback looks right to me");
    }
  }

  // ---------------------------------------------------------------------- fixtures

  private static IncidentReportRenderer.ReportInputs inputs(
      int evidenceCount, List<EvidenceGap> gaps, Optional<RecoveryVerification> verification) {

    return new IncidentReportRenderer.ReportInputs(
        incident(IncidentStatus.RESOLVED),
        evidence(evidenceCount),
        gaps,
        auditTrail(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        false,
        verification);
  }

  private static Incident incident(IncidentStatus status) {
    Incident opened =
        Incident.open(
            IncidentId.newId(),
            "Checkout latency above objective",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            T0);

    // Built by walking the real state machine rather than by constructing the record directly, so
    // the fixture cannot describe a state the system could never reach.
    return opened
        .transitionTo(IncidentStatus.INVESTIGATING, T0.plus(Duration.ofMinutes(1)))
        .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, T0.plus(Duration.ofMinutes(4)))
        .transitionTo(IncidentStatus.PLANNING_REMEDIATION, T0.plus(Duration.ofMinutes(6)))
        .transitionTo(IncidentStatus.AWAITING_APPROVAL, T0.plus(Duration.ofMinutes(7)))
        .transitionTo(IncidentStatus.REMEDIATING, T0.plus(Duration.ofMinutes(20)))
        .transitionTo(IncidentStatus.VERIFYING, T0.plus(Duration.ofMinutes(22)))
        .close(status, "Closed by test fixture", T0.plus(Duration.ofMinutes(30)));
  }

  private static List<Evidence> evidence(int count) {
    IncidentId incidentId = IncidentId.newId();
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(
            index ->
                new Evidence(
                    EvidenceId.newId(),
                    incidentId,
                    EvidenceSource.CLOUDWATCH_METRICS,
                    "metrics_investigator",
                    "observation " + index,
                    "content " + index,
                    Confidence.clamped(0.9),
                    T0.plus(Duration.ofSeconds(index)),
                    Optional.empty()))
        .toList();
  }

  private static List<AuditEvent> auditTrail() {
    IncidentId incidentId = IncidentId.newId();
    return List.of(
        AuditEvent.of(
            incidentId,
            AuditEventType.INCIDENT_OPENED,
            Actor.SYSTEM,
            "Incident opened from alarm checkout-p99-latency-high",
            Map.of(),
            T0),
        AuditEvent.of(
            incidentId,
            AuditEventType.APPROVAL_GRANTED,
            approver(),
            "Human approved the rollback",
            Map.of(),
            T0.plus(Duration.ofMinutes(19))));
  }

  private static Actor approver() {
    return new Actor("dana", "Dana Approver", ActorRole.APPROVER);
  }

  private static ApprovalRequest approval() {
    ProposedAction action =
        new ProposedAction(
            ActionType.ROLLBACK_DEPLOYMENT,
            new ResourceRef(
                "arn:aws:ecs:eu-west-1:123456789012:service/commander-demo/checkout",
                "123456789012",
                "eu-west-1",
                "demo",
                "ecs:service"),
            Map.of(),
            "Roll checkout back to task definition checkout:44");

    return ApprovalRequest.pending(
        new ApprovalId(java.util.UUID.nameUUIDFromBytes("approval".getBytes())),
        new IncidentId(java.util.UUID.nameUUIDFromBytes("incident".getBytes())),
        6L,
        action,
        RiskLevel.MEDIUM,
        "The latency step coincides exactly with the deployment.",
        "Checkout tasks are replaced with the previous revision.",
        List.of(EvidenceId.newId()),
        T0.plus(Duration.ofMinutes(7)),
        T0.plus(Duration.ofMinutes(67)),
        "call-1");
  }

  private static ApprovalDecision decision() {
    return ApprovalDecision.approve(
        approval().id(),
        approver(),
        approval().fingerprint(),
        T0.plus(Duration.ofMinutes(19)),
        "Rollback looks right to me");
  }
}
