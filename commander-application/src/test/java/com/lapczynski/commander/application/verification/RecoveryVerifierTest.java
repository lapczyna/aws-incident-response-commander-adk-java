package com.lapczynski.commander.application.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.audit.AuditEventType;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Verifying that a remediation worked")
class RecoveryVerifierTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final String METRIC = "TargetResponseTimeP99";
  private static final double BEFORE = 1.15;
  private static final double THRESHOLD = 0.4;

  @Mock private MetricsPort metrics;
  @Mock private EvidenceRepository evidence;
  @Mock private ExecutionRepository executions;
  @Mock private VerificationRepository verifications;
  @Mock private IncidentRepository incidents;
  @Mock private AuditLog auditLog;

  private RecoveryVerifier verifier;
  private IncidentId incidentId;

  @BeforeEach
  void setUp() {
    verifier =
        new RecoveryVerifier(
            metrics,
            evidence,
            executions,
            verifications,
            incidents,
            auditLog,
            Clock.fixed(NOW, ZoneOffset.UTC));
    incidentId = IncidentId.newId();
  }

  @Nested
  @DisplayName("when the symptom is gone")
  class Recovered {

    @Test
    @DisplayName("the incident is resolved")
    void resolves() {
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      givenSeries(0.18, 0.20, 0.19);

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.outcome()).isEqualTo(RecoveryVerification.Outcome.RECOVERED);
      assertThat(verification.permitsResolution()).isTrue();
      assertThat(closedStatus()).isEqualTo(IncidentStatus.RESOLVED);
      assertThat(auditType()).isEqualTo(AuditEventType.VERIFICATION_SUCCEEDED);
    }

    @Test
    @DisplayName("the measurement is stored as citable evidence")
    void storesEvidence() {
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      givenSeries(0.18, 0.20, 0.19);

      RecoveryVerification verification = verifier.verify(request());

      ArgumentCaptor<Evidence> stored = ArgumentCaptor.forClass(Evidence.class);
      verify(evidence).save(stored.capture());

      // The verification cites the row that was actually written, not a fresh id. If these
      // diverged the report would print a reference that resolves to nothing.
      assertThat(verification.supportingEvidence()).containsExactly(stored.getValue().id());
      assertThat(stored.getValue().content()).contains("average=");
    }
  }

  @Nested
  @DisplayName("when the symptom persists")
  class NotRecovered {

    @Test
    @DisplayName("the incident fails rather than resolving")
    void fails() {
      // Scenario 8: the rollback completed, the diagnosis was wrong, and latency never moved.
      // Reporting success because the action succeeded is the specific failure this exists to
      // prevent.
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      givenSeries(1.14, 1.16, 1.15);

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.outcome()).isEqualTo(RecoveryVerification.Outcome.NOT_RECOVERED);
      assertThat(closedStatus()).isEqualTo(IncidentStatus.FAILED);
      assertThat(auditType()).isEqualTo(AuditEventType.VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("the summary says the diagnosis was wrong, not that the action failed")
    void blamesTheDiagnosis() {
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      givenSeries(1.14, 1.16, 1.15);

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.summary()).contains("diagnosis was wrong");
    }
  }

  @Nested
  @DisplayName("when the metric cannot be read")
  class Unmeasurable {

    @Test
    @DisplayName("a source failure is indeterminate, and the incident does not resolve")
    void sourceFailure() {
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      when(metrics.query(any()))
          .thenThrow(
              new SignalSourceException(EvidenceGap.Reason.TIMEOUT, "CloudWatch did not answer"));

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.outcome()).isEqualTo(RecoveryVerification.Outcome.INDETERMINATE);
      assertThat(verification.afterValue()).isNaN();
      assertThat(closedStatus()).isEqualTo(IncidentStatus.FAILED);
    }

    @Test
    @DisplayName("the failure is recorded as a gap so the report can explain itself")
    void recordsGap() {
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      when(metrics.query(any()))
          .thenThrow(
              new SignalSourceException(
                  EvidenceGap.Reason.ACCESS_DENIED, "no permission for GetMetricStatistics"));

      verifier.verify(request());

      ArgumentCaptor<EvidenceGap> gap = ArgumentCaptor.forClass(EvidenceGap.class);
      verify(evidence).saveGap(gap.capture());
      assertThat(gap.getValue().reason()).isEqualTo(EvidenceGap.Reason.ACCESS_DENIED);
    }

    @Test
    @DisplayName("an empty series is indeterminate, not recovery")
    void emptySeries() {
      // A service emitting no latency datapoints after a restart is more likely to be down than
      // healthy, so silence is not evidence of success.
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      when(metrics.query(any()))
          .thenReturn(new MetricsPort.MetricSeries(METRIC, "Seconds", List.of()));

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.outcome()).isEqualTo(RecoveryVerification.Outcome.INDETERMINATE);
      assertThat(closedStatus()).isEqualTo(IncidentStatus.FAILED);
    }

    @Test
    @DisplayName("an indeterminate verdict still cites something")
    void stillCitesSomething() {
      // The domain refuses a verification with no supporting evidence, and the honest citation
      // for "we could not measure" is a record saying exactly that — not a fabricated datapoint.
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofMinutes(10)));
      when(metrics.query(any()))
          .thenReturn(new MetricsPort.MetricSeries(METRIC, "Seconds", List.of()));

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.supportingEvidence()).isNotEmpty();

      ArgumentCaptor<Evidence> stored = ArgumentCaptor.forClass(Evidence.class);
      verify(evidence).save(stored.capture());
      assertThat(stored.getValue().summary()).contains("could not be measured");
      assertThat(stored.getValue().confidence().value()).isZero();
    }
  }

  @Nested
  @DisplayName("guards")
  class Guards {

    @Test
    @DisplayName("asking before the observation window is complete defers rather than failing")
    void tooSoon() {
      // Concluding NOT_RECOVERED because we asked early would blame a fix that may have worked,
      // and would close the incident on that basis.
      givenIncidentVerifying();
      givenExecutionFinishedAt(NOW.minus(Duration.ofSeconds(30)));

      assertThatThrownBy(() -> verifier.verify(request()))
          .isInstanceOf(TooSoonToVerifyException.class);

      verify(verifications, never()).save(any(), any());
      verify(incidents, never()).update(any(), anyLong(), any(), any());
      verify(metrics, never()).query(any());
    }

    @Test
    @DisplayName("verifying an incident that is not VERIFYING is refused")
    void wrongStatus() {
      when(incidents.findById(incidentId))
          .thenReturn(Optional.of(openIncident().transitionTo(IncidentStatus.INVESTIGATING, NOW)));

      assertThatThrownBy(() -> verifier.verify(request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("VERIFYING");
    }

    @Test
    @DisplayName("verifying an unknown incident is refused")
    void unknownIncident() {
      when(incidents.findById(incidentId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> verifier.verify(request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unknown incident");
    }

    @Test
    @DisplayName("an incident with no execution at all can still be verified")
    void noExecution() {
      // A dry-run demo never writes an execution row. Refusing to verify would leave the incident
      // stuck in VERIFYING forever, which is worse than measuring against a window that starts
      // wherever it starts.
      givenIncidentVerifying();
      when(executions.findLatestForIncident(incidentId)).thenReturn(Optional.empty());
      givenSeries(0.19);

      RecoveryVerification verification = verifier.verify(request());

      assertThat(verification.outcome()).isEqualTo(RecoveryVerification.Outcome.RECOVERED);
    }
  }

  // ---------------------------------------------------------------------- fixtures

  private RecoveryVerifier.VerificationRequest request() {
    return RecoveryVerifier.VerificationRequest.of(
        incidentId, "checkout", METRIC, BEFORE, THRESHOLD);
  }

  private Incident openIncident() {
    return Incident.open(
        incidentId,
        "Checkout latency above objective",
        new ServiceRef("checkout", "demo"),
        Severity.SEV2,
        NOW.minus(Duration.ofHours(1)));
  }

  private void givenIncidentVerifying() {
    Incident verifying =
        openIncident()
            .transitionTo(IncidentStatus.INVESTIGATING, NOW.minus(Duration.ofMinutes(50)))
            .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, NOW.minus(Duration.ofMinutes(45)))
            .transitionTo(IncidentStatus.PLANNING_REMEDIATION, NOW.minus(Duration.ofMinutes(40)))
            .transitionTo(IncidentStatus.AWAITING_APPROVAL, NOW.minus(Duration.ofMinutes(35)))
            .transitionTo(IncidentStatus.REMEDIATING, NOW.minus(Duration.ofMinutes(20)))
            .transitionTo(IncidentStatus.VERIFYING, NOW.minus(Duration.ofMinutes(15)));

    when(incidents.findById(incidentId)).thenReturn(Optional.of(verifying));
  }

  private void givenExecutionFinishedAt(Instant finishedAt) {
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
            "Roll checkout back one revision");

    when(executions.findLatestForIncident(incidentId))
        .thenReturn(
            Optional.of(
                new ExecutedAction(
                    UUID.randomUUID(),
                    incidentId,
                    Optional.of(new ApprovalId(UUID.randomUUID())),
                    ActionFingerprint.of(action, incidentId, 6L),
                    ActionType.ROLLBACK_DEPLOYMENT,
                    action.target().arn(),
                    false,
                    ExecutedAction.Outcome.SUCCEEDED,
                    Optional.of("Forced a new deployment"),
                    finishedAt.minus(Duration.ofSeconds(20)),
                    Optional.of(finishedAt))));
  }

  private void givenSeries(double... values) {
    List<MetricsPort.MetricPoint> points = new ArrayList<>();
    for (int index = 0; index < values.length; index++) {
      points.add(
          new MetricsPort.MetricPoint(
              NOW.minus(Duration.ofMinutes(values.length - index)), values[index]));
    }
    when(metrics.query(any())).thenReturn(new MetricsPort.MetricSeries(METRIC, "Seconds", points));
  }

  private IncidentStatus closedStatus() {
    ArgumentCaptor<Incident> updated = ArgumentCaptor.forClass(Incident.class);
    verify(incidents).update(updated.capture(), anyLong(), any(Actor.class), any(String.class));
    return updated.getValue().status();
  }

  private AuditEventType auditType() {
    ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditLog).append(appended.capture());
    return appended.getValue().type();
  }
}
