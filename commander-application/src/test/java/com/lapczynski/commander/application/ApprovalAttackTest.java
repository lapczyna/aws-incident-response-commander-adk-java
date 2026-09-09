package com.lapczynski.commander.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lapczynski.commander.application.port.ApprovalException;
import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import com.lapczynski.commander.domain.approval.ApprovalDecision;
import com.lapczynski.commander.domain.approval.ApprovalId;
import com.lapczynski.commander.domain.approval.ApprovalRequest;
import com.lapczynski.commander.domain.approval.ApprovalStatus;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Attacks on the approval gate, written from the attacker's point of view.
 *
 * <p>Each test names a way someone might try to get an action executed that a human did not
 * knowingly authorise. Some of these properties are also covered elsewhere — the replay path has an
 * integration test against a real unique constraint, for instance — and they are restated here
 * because a security control that is only tested incidentally, as a side effect of a happy-path
 * test, is one nobody will notice losing.
 *
 * <p>The one thing every test asserts in common: nothing was executed, and the refusal was audited.
 * A refusal that leaves no trace is indistinguishable from an attack that was never attempted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Attacks on the approval gate")
class ApprovalAttackTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

  private static final Actor APPROVER = new Actor("dana", "Dana", ActorRole.APPROVER);
  private static final Actor INVESTIGATOR = new Actor("sam", "Sam", ActorRole.INVESTIGATOR);
  private static final Actor VIEWER = new Actor("kim", "Kim", ActorRole.VIEWER);

  @Mock private ApprovalRepository approvals;
  @Mock private IncidentRepository incidents;
  @Mock private AuditLog auditLog;

  private ApprovalService service;
  private IncidentId incidentId;
  private ApprovalId approvalId;

  @BeforeEach
  void setUp() {
    service = new ApprovalService(approvals, incidents, auditLog, Clock.fixed(NOW, ZoneOffset.UTC));
    incidentId = IncidentId.newId();
    approvalId = ApprovalId.newId();
  }

  @Nested
  @DisplayName("impersonation and privilege")
  class Impersonation {

    @Test
    @DisplayName("a viewer cannot approve an action")
    void viewerCannotApprove() {
      givenPendingApproval(6L);

      assertThatThrownBy(() -> service.approve(approvalId, VIEWER, "looks fine"))
          .isInstanceOf(ApprovalException.Rejected.class);

      assertNothingWasDecided();
      assertRefusalWasAudited();
    }

    @Test
    @DisplayName("an investigator cannot approve an action")
    void investigatorCannotApprove() {
      // The role that can drive an investigation is deliberately not the role that can authorise
      // acting on one.
      givenPendingApproval(6L);

      assertThatThrownBy(() -> service.approve(approvalId, INVESTIGATOR, "ship it"))
          .isInstanceOf(ApprovalException.Rejected.class);

      assertNothingWasDecided();
    }

    @Test
    @DisplayName("the system cannot approve its own remediation")
    void systemCannotSelfApprove() {
      // The governing constraint of the whole project, at its narrowest point: if this passed, an
      // LLM-driven pipeline could authorise its own production change.
      givenPendingApproval(6L);

      assertThatThrownBy(() -> service.approve(approvalId, Actor.SYSTEM, "proceeding"))
          .isInstanceOf(ApprovalException.Rejected.class);

      assertNothingWasDecided();
    }

    @Test
    @DisplayName("the human who opened the incident cannot approve acting on it")
    void openerCannotApprove() {
      // Separation of duties. Someone who can raise incidents could otherwise raise one that
      // justifies the action they wanted and then approve it themselves.
      Actor opener = new Actor("dana", "Dana", ActorRole.APPROVER);

      givenPendingApproval(6L);
      when(incidents.openedBy(incidentId)).thenReturn(Optional.of(opener));

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "approving my own report"))
          .isInstanceOf(ApprovalException.Rejected.class)
          .extracting(e -> ((ApprovalException.Rejected) e).reason())
          .isInstanceOf(ApprovalException.NotAuthorised.class);

      assertNothingWasDecided();
    }

    @Test
    @DisplayName("an incident opened by the system may still be approved by a human")
    void systemOpenedIncidentsAreApprovable() {
      // The normal path. If the separation-of-duties rule caught this, nobody could approve
      // anything, and the rule would be removed rather than fixed.
      givenPendingApproval(6L);
      when(incidents.findById(incidentId)).thenReturn(Optional.of(incident(6L)));
      when(incidents.openedBy(incidentId)).thenReturn(Optional.of(Actor.SYSTEM));

      assertThat(service.approve(approvalId, APPROVER, "go ahead")).isNotNull();
    }
  }

  @Nested
  @DisplayName("forgery and staleness")
  class Forgery {

    @Test
    @DisplayName("an approval whose incident has moved on is refused as stale")
    void staleApproval() {
      // The reason the fingerprint exists. A human approved an action given the evidence they saw;
      // if the incident has changed since, that decision does not cover the new situation.
      givenPendingApproval(6L);
      when(incidents.findById(incidentId)).thenReturn(Optional.of(incident(9L)));

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "yes"))
          .isInstanceOf(ApprovalException.Rejected.class)
          .extracting(e -> ((ApprovalException.Rejected) e).reason())
          .isInstanceOf(ApprovalException.Stale.class);

      assertNothingWasDecided();
      verify(approvals).updateStatus(approvalId, ApprovalStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("an approval for an incident that no longer exists is refused")
    void missingIncident() {
      givenPendingApproval(6L);
      when(incidents.findById(incidentId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "yes"))
          .isInstanceOf(ApprovalException.Rejected.class);

      assertNothingWasDecided();
    }

    @Test
    @DisplayName("an approval id that was never issued is refused")
    void unknownApproval() {
      when(approvals.findById(approvalId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "yes"))
          .isInstanceOf(ApprovalException.Rejected.class);

      assertNothingWasDecided();
    }

    @Test
    @DisplayName("the fingerprint recorded on a decision is the one derived from current state")
    void decisionCarriesTheLiveFingerprint() {
      // A decision that stored an attacker-supplied fingerprint would let the approved action and
      // the executed action differ. It is recomputed, never accepted as input.
      ApprovalRequest request = givenPendingApproval(6L);
      when(incidents.findById(incidentId)).thenReturn(Optional.of(incident(6L)));

      ActionFingerprint authorised = service.approve(approvalId, APPROVER, "go");

      ArgumentCaptor<ApprovalDecision> decision = ArgumentCaptor.forClass(ApprovalDecision.class);
      verify(approvals).recordDecision(decision.capture());

      assertThat(decision.getValue().fingerprintAtDecision()).isEqualTo(request.fingerprint());
      assertThat(authorised).isEqualTo(request.fingerprint());
    }
  }

  @Nested
  @DisplayName("replay and timing")
  class ReplayAndTiming {

    @Test
    @DisplayName("an approval that was already decided cannot be decided again")
    void replayRefused() {
      ApprovalRequest request = givenPendingApproval(6L);
      when(approvals.findDecision(approvalId))
          .thenReturn(
              Optional.of(
                  ApprovalDecision.approve(
                      approvalId, APPROVER, request.fingerprint(), NOW.minusSeconds(60), "yes")));

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "yes again"))
          .isInstanceOf(ApprovalException.Rejected.class)
          .extracting(e -> ((ApprovalException.Rejected) e).reason())
          .isInstanceOf(ApprovalException.AlreadyDecided.class);

      verify(approvals, never()).recordDecision(any());
    }

    @Test
    @DisplayName("a rejected approval cannot be flipped to approved")
    void rejectedCannotBeApproved() {
      givenApprovalWithStatus(ApprovalStatus.REJECTED, 6L);

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "changed my mind"))
          .isInstanceOf(ApprovalException.Rejected.class);

      verify(approvals, never()).recordDecision(any());
    }

    @Test
    @DisplayName("an expired approval is refused, and expiry is applied on read")
    void expiredApproval() {
      // Expiry is evaluated here rather than only by a sweep. A sweep that fails to run would
      // otherwise silently extend the window in which an approval can be used.
      givenApprovalExpiringAt(NOW.minusSeconds(1), 6L);

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "yes"))
          .isInstanceOf(ApprovalException.Rejected.class)
          .extracting(e -> ((ApprovalException.Rejected) e).reason())
          .isInstanceOf(ApprovalException.Expired.class);

      verify(approvals).updateStatus(approvalId, ApprovalStatus.EXPIRED);
      verify(approvals, never()).recordDecision(any());
    }

    @Test
    @DisplayName("an approval expiring exactly now is already expired")
    void expiryBoundaryIsClosed() {
      // The boundary is chosen against the attacker: a deadline that had not yet passed at exactly
      // its own instant would leave a window whose width depends on clock resolution.
      givenApprovalExpiringAt(NOW, 6L);

      assertThatThrownBy(() -> service.approve(approvalId, APPROVER, "just in time"))
          .isInstanceOf(ApprovalException.Rejected.class);
    }
  }

  @Nested
  @DisplayName("what a refusal leaves behind")
  class Auditing {

    @Test
    @DisplayName("every refusal is audited as security-relevant")
    void refusalsAreAudited() {
      givenPendingApproval(6L);

      assertThatThrownBy(() -> service.approve(approvalId, VIEWER, "hello"))
          .isInstanceOf(ApprovalException.Rejected.class);

      ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
      verify(auditLog).append(event.capture());
      assertThat(event.getValue().isSecurityRelevant()).isTrue();
    }
  }

  // ---------------------------------------------------------------------- fixtures

  private ApprovalRequest givenPendingApproval(long incidentVersion) {
    return givenApproval(ApprovalStatus.PENDING, incidentVersion, NOW.plus(Duration.ofMinutes(30)));
  }

  private ApprovalRequest givenApprovalWithStatus(ApprovalStatus status, long incidentVersion) {
    return givenApproval(status, incidentVersion, NOW.plus(Duration.ofMinutes(30)));
  }

  private ApprovalRequest givenApprovalExpiringAt(Instant expiresAt, long incidentVersion) {
    return givenApproval(ApprovalStatus.PENDING, incidentVersion, expiresAt);
  }

  private ApprovalRequest givenApproval(
      ApprovalStatus status, long incidentVersion, Instant expiresAt) {

    ApprovalRequest request =
        ApprovalRequest.pending(
                approvalId,
                incidentId,
                incidentVersion,
                action(),
                RiskLevel.MEDIUM,
                "The latency step coincides with the deployment.",
                "Both tasks are replaced with the previous revision.",
                List.of(EvidenceId.newId()),
                NOW.minus(Duration.ofMinutes(5)),
                expiresAt,
                "call-1")
            .withStatus(status);

    when(approvals.findById(approvalId)).thenReturn(Optional.of(request));
    return request;
  }

  private Incident incident(long version) {
    Incident opened =
        Incident.open(
            incidentId,
            "Checkout latency above objective",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            NOW.minus(Duration.ofMinutes(30)));

    // Walked to the target version rather than constructed, so the fixture cannot describe an
    // incident the state machine could not produce.
    Incident current =
        opened
            .transitionTo(IncidentStatus.INVESTIGATING, NOW.minus(Duration.ofMinutes(25)))
            .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, NOW.minus(Duration.ofMinutes(20)))
            .transitionTo(IncidentStatus.PLANNING_REMEDIATION, NOW.minus(Duration.ofMinutes(15)))
            .transitionTo(IncidentStatus.AWAITING_APPROVAL, NOW.minus(Duration.ofMinutes(10)));

    while (current.version() < version) {
      current = current.withSummary("summary at version " + current.version(), NOW);
    }
    return current;
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
        Map.of(),
        "Roll checkout back one revision");
  }

  private void assertNothingWasDecided() {
    verify(approvals, never()).recordDecision(any());
    verify(approvals, never()).updateStatus(approvalId, ApprovalStatus.APPROVED);
  }

  private void assertRefusalWasAudited() {
    verify(auditLog).append(any(AuditEvent.class));
  }
}
