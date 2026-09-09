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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the sample postmortem in the documentation honest.
 *
 * <p>The sample in {@code docs/samples/} is not written by hand. It is rendered by the real {@link
 * IncidentReportRenderer} from the fixture below and compared against the committed file, so a
 * change to the renderer that would alter what a reader sees fails this test rather than quietly
 * leaving the documentation describing a report the code no longer produces.
 *
 * <p>Run with {@code -Dsamples.update=true} to rewrite the file after an intentional change.
 *
 * <p>Every value here is fabricated for the sample. It is modelled on simulator scenario 8 — a
 * convincing diagnosis, a rollback that completed, and a service that stayed broken — because that
 * is the report worth showing: one where the system had to say the fix did not work.
 */
class IncidentReportSampleTest {

  private static final Path SAMPLE =
      Path.of("..", "docs", "samples", "postmortem-checkout-latency.md");

  private static final Instant T0 = Instant.parse("2026-09-09T09:14:00Z");
  private static final IncidentId INCIDENT =
      new IncidentId(UUID.fromString("7c9e6f0e-5a3d-4b1c-9f2a-8d4e1b6c3a70"));
  private static final ApprovalId APPROVAL =
      new ApprovalId(UUID.fromString("2f1a8c4b-6d7e-4a90-b3c5-1e8f7d2a4b60"));

  private static final Actor APPROVER = new Actor("r.okafor", "Rachel Okafor", ActorRole.APPROVER);

  @Test
  @DisplayName("the documented sample postmortem is what the renderer actually produces")
  void sampleIsCurrent() throws IOException {
    String rendered = new IncidentReportRenderer().render(inputs(), narrative());

    if (Boolean.getBoolean("samples.update") || Files.notExists(SAMPLE)) {
      Files.createDirectories(SAMPLE.getParent());
      Files.writeString(SAMPLE, rendered, StandardCharsets.UTF_8);
    }

    String committed = Files.readString(SAMPLE, StandardCharsets.UTF_8);

    assertThat(committed.replace("\r\n", "\n"))
        .describedAs(
            "docs/samples/postmortem-checkout-latency.md is stale. Re-run with "
                + "-Dsamples.update=true to regenerate it, then review the diff.")
        .isEqualTo(rendered.replace("\r\n", "\n"));
  }

  @Test
  @DisplayName("the sample shows the case worth showing: a fix that did not work")
  void sampleShowsAFailedRemediation() throws IOException {
    // A sample postmortem of a tidy success would demonstrate the formatting and none of the
    // engineering. This asserts the documentation keeps showing the hard case.
    String committed = Files.readString(SAMPLE, StandardCharsets.UTF_8);

    assertThat(committed).contains("This incident is not resolved");
    assertThat(committed).contains("NOT_RECOVERED");
    assertThat(committed).contains("## Missing evidence");
  }

  // ---------------------------------------------------------------------- the fixture

  private static String narrative() {
    return """
        ### What happened

        Checkout p99 latency stepped from roughly 0.21s to 1.15s at 09:14 UTC and stayed there
        [E1]. Error responses rose at the same moment [E2]. A deployment of checkout 2.6.0
        completed within the same minute [E4], and the latency alarm crossed its threshold three
        minutes later [E3]. Rolling that deployment back did not change the latency [E6].

        ### Why it happened

        The evidence does not support the deployment as the cause. Log lines from one minute
        before the deploy show DNS resolution for `inventory-api.internal` taking 2.8 seconds
        [E5], and upstream calls to that service exceeding their soft timeout by a factor of six
        [E5]. The deployment coincided with the onset rather than causing it, and the rollback
        addressed the coincidence. What actually degraded the resolver is not visible in the
        evidence collected here.

        ### What we learned

        Two things would have separated these. First, nothing alerts on upstream DNS resolution
        time, so the one signal that pointed away from the deployment was only visible by reading
        log lines [E5]. Second, the latency step and the deploy were correlated on a one-minute
        boundary; a finer timestamp on either would have shown the latency moving first.
        """;
  }

  private static IncidentReportRenderer.ReportInputs inputs() {
    return new IncidentReportRenderer.ReportInputs(
        incident(),
        evidence(),
        gaps(),
        auditTrail(),
        Optional.of(approval()),
        Optional.of(decision()),
        Optional.of(
            "Forced a new deployment of checkout at revision checkout:44. Two tasks were "
                + "replaced."),
        false,
        Optional.of(verification()));
  }

  private static Incident incident() {
    return Incident.open(
            INCIDENT,
            "Checkout p99 latency above objective",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            T0)
        .transitionTo(IncidentStatus.INVESTIGATING, T0.plus(Duration.ofMinutes(1)))
        .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, T0.plus(Duration.ofMinutes(4)))
        .transitionTo(IncidentStatus.PLANNING_REMEDIATION, T0.plus(Duration.ofMinutes(6)))
        .transitionTo(IncidentStatus.AWAITING_APPROVAL, T0.plus(Duration.ofMinutes(7)))
        .transitionTo(IncidentStatus.REMEDIATING, T0.plus(Duration.ofMinutes(19)))
        .transitionTo(IncidentStatus.VERIFYING, T0.plus(Duration.ofMinutes(21)))
        .close(
            IncidentStatus.FAILED,
            "Latency did not recover after the rollback.",
            T0.plus(Duration.ofMinutes(29)));
  }

  private static List<Evidence> evidence() {
    return List.of(
        observation(
            EvidenceSource.CLOUDWATCH_METRICS,
            "metrics_investigator",
            "TargetResponseTimeP99 stepped from 0.21s to 1.15s at 09:14 and held",
            1),
        observation(
            EvidenceSource.CLOUDWATCH_METRICS,
            "metrics_investigator",
            "HTTPCode_Target_5XX_Count rose from 0.5/min to 3.0/min at 09:14",
            2),
        observation(
            EvidenceSource.CLOUDWATCH_ALARMS,
            "metrics_investigator",
            "Alarm checkout-p99-latency-high entered ALARM at 09:17 (threshold 0.4)",
            3),
        observation(
            EvidenceSource.CLOUDTRAIL_CHANGES,
            "change_investigator",
            "UpdateService by ci-deploy-role moved checkout to checkout:45 at 09:14",
            4),
        observation(
            EvidenceSource.CLOUDWATCH_LOGS,
            "logs_investigator",
            "22 warnings: DNS resolution for inventory-api.internal took 2840ms, from 09:13",
            5),
        observation(
            EvidenceSource.CLOUDWATCH_METRICS,
            "recovery-verifier",
            "TargetResponseTimeP99 averaged 1.147 over PT5M after remediation (was 1.15, "
                + "recovery threshold 0.4)",
            6));
  }

  private static Evidence observation(
      EvidenceSource source, String collectedBy, String summary, int index) {

    return new Evidence(
        new EvidenceId(
            UUID.nameUUIDFromBytes(("evidence-" + index).getBytes(StandardCharsets.UTF_8))),
        INCIDENT,
        source,
        collectedBy,
        summary,
        "Full content is stored but omitted from this sample.",
        Confidence.clamped(0.9),
        T0.plus(Duration.ofMinutes(2)).plusSeconds(index),
        Optional.empty());
  }

  private static List<EvidenceGap> gaps() {
    return List.of(
        new EvidenceGap(
            INCIDENT,
            EvidenceSource.SERVICE_HEALTH,
            "ecs_investigator",
            EvidenceGap.Reason.ACCESS_DENIED,
            "The upstream inventory-api is outside this account; its health was not readable",
            T0.plus(Duration.ofMinutes(3))));
  }

  private static List<AuditEvent> auditTrail() {
    return List.of(
        audit(
            AuditEventType.INCIDENT_OPENED,
            Actor.SYSTEM,
            "Incident opened from alarm checkout-p99-latency-high",
            0),
        audit(
            AuditEventType.EVIDENCE_RECORDED,
            Actor.SYSTEM,
            "Four specialists reported: 6 observations, 1 gap",
            3),
        audit(
            AuditEventType.HYPOTHESIS_PROPOSED,
            Actor.SYSTEM,
            "Deployment checkout:45 caused the latency step (confidence 0.78)",
            5),
        audit(
            AuditEventType.REMEDIATION_PROPOSED,
            Actor.SYSTEM,
            "Proposed ROLLBACK_DEPLOYMENT of checkout to checkout:44",
            6),
        audit(
            AuditEventType.POLICY_EVALUATED,
            Actor.SYSTEM,
            "Policy allowed the action; risk MEDIUM; human approval required",
            6),
        audit(AuditEventType.APPROVAL_REQUESTED, Actor.SYSTEM, "Awaiting a human decision", 7),
        audit(
            AuditEventType.APPROVAL_GRANTED,
            APPROVER,
            "Approved: the timing correlation is convincing enough to try",
            18),
        audit(
            AuditEventType.ACTION_EXECUTED,
            Actor.SYSTEM,
            "ROLLBACK_DEPLOYMENT executed; two tasks replaced at checkout:44",
            19),
        audit(
            AuditEventType.VERIFICATION_FAILED,
            Actor.SYSTEM,
            "TargetResponseTimeP99 is still 1.147 against a recovery threshold of 0.4",
            29),
        audit(
            AuditEventType.INCIDENT_STATUS_CHANGED,
            Actor.SYSTEM,
            "VERIFYING to FAILED: recovery verification did not pass",
            29));
  }

  private static AuditEvent audit(AuditEventType type, Actor actor, String summary, int minutesIn) {
    return AuditEvent.of(
        INCIDENT, type, actor, summary, Map.of(), T0.plus(Duration.ofMinutes(minutesIn)));
  }

  private static ProposedAction action() {
    return new ProposedAction(
        ActionType.ROLLBACK_DEPLOYMENT,
        new ResourceRef(
            "arn:aws:ecs:eu-west-1:123456789012:service/commander-demo/checkout",
            "123456789012",
            "eu-west-1",
            "demo",
            "ecs:service"),
        Map.of("targetRevision", "checkout:44"),
        "Roll the checkout service back from checkout:45 to checkout:44");
  }

  private static ApprovalRequest approval() {
    return ApprovalRequest.pending(
        APPROVAL,
        INCIDENT,
        6L,
        action(),
        RiskLevel.MEDIUM,
        "The latency step and the deployment share a one-minute timestamp, and no other change "
            + "is visible in the window.",
        "Both checkout tasks are replaced with the previous revision. Requests in flight are "
            + "drained; brief additional latency is expected during the replacement.",
        List.of(evidence().get(0).id(), evidence().get(2).id(), evidence().get(3).id()),
        T0.plus(Duration.ofMinutes(7)),
        T0.plus(Duration.ofMinutes(67)),
        "call-8f3a1c");
  }

  private static ApprovalDecision decision() {
    return ApprovalDecision.approve(
        APPROVAL,
        APPROVER,
        approval().fingerprint(),
        T0.plus(Duration.ofMinutes(18)),
        "Timing is convincing. Roll it back and watch p99 for five minutes.");
  }

  private static RecoveryVerification verification() {
    return new RecoveryVerification(
        INCIDENT,
        "TargetResponseTimeP99",
        1.15,
        1.147,
        0.4,
        RecoveryVerification.Outcome.NOT_RECOVERED,
        "TargetResponseTimeP99 is still 1.147 against a recovery threshold of 0.4. The action "
            + "completed, but the symptom persists, which means the diagnosis was wrong rather "
            + "than the action.",
        List.of(evidence().get(5).id()),
        T0.plus(Duration.ofMinutes(29)));
  }
}
