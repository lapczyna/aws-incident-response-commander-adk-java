package com.lapczynski.commander.domain.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The state machine is what gates remediation, so it is tested exhaustively rather than by example.
 * Every one of the 121 ordered status pairs is exercised: the legal ones must succeed, and every
 * other pair must throw.
 */
class IncidentStatusTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

  /** The transition table, restated independently of the production switch. */
  private static final Set<String> EXPECTED_LEGAL_TRANSITIONS =
      Set.of(
          "RECEIVED->INVESTIGATING",
          "RECEIVED->CANCELLED",
          "RECEIVED->FAILED",
          "INVESTIGATING->FORMING_HYPOTHESIS",
          "INVESTIGATING->CANCELLED",
          "INVESTIGATING->FAILED",
          "FORMING_HYPOTHESIS->PLANNING_REMEDIATION",
          "FORMING_HYPOTHESIS->RESOLVED",
          "FORMING_HYPOTHESIS->CANCELLED",
          "FORMING_HYPOTHESIS->FAILED",
          "PLANNING_REMEDIATION->AWAITING_APPROVAL",
          "PLANNING_REMEDIATION->RESOLVED",
          "PLANNING_REMEDIATION->CANCELLED",
          "PLANNING_REMEDIATION->FAILED",
          "AWAITING_APPROVAL->REMEDIATING",
          "AWAITING_APPROVAL->REJECTED",
          "AWAITING_APPROVAL->CANCELLED",
          "REMEDIATING->VERIFYING",
          "REMEDIATING->FAILED",
          "VERIFYING->RESOLVED",
          "VERIFYING->FAILED");

  @Test
  @DisplayName("the full transition matrix matches the specification, pair by pair")
  void exhaustiveTransitionMatrix() {
    SoftAssertions softly = new SoftAssertions();
    List<String> illegalAccepted = new ArrayList<>();

    for (IncidentStatus from : IncidentStatus.values()) {
      for (IncidentStatus to : IncidentStatus.values()) {
        String pair = from + "->" + to;
        boolean expectedLegal = EXPECTED_LEGAL_TRANSITIONS.contains(pair);
        boolean actualLegal = from.canTransitionTo(to);

        softly
            .assertThat(actualLegal)
            .as("transition %s should be %s", pair, expectedLegal ? "legal" : "illegal")
            .isEqualTo(expectedLegal);

        if (actualLegal && !expectedLegal) {
          illegalAccepted.add(pair);
        }
      }
    }

    softly
        .assertThat(illegalAccepted)
        .as(
            "transitions accepted that the specification forbids - each is a path to acting "
                + "outside the approved lifecycle")
        .isEmpty();
    softly.assertAll();
  }

  @Test
  @DisplayName("every status pair is covered, so the matrix cannot silently shrink")
  void matrixCoversEveryPair() {
    int statuses = IncidentStatus.values().length;
    assertThat(statuses * statuses)
        .as("expected the full cartesian product of statuses to be exercised")
        .isEqualTo(121);
  }

  @ParameterizedTest
  @EnumSource(IncidentStatus.class)
  @DisplayName("no status transitions to itself")
  void noSelfTransitions(IncidentStatus status) {
    assertThat(status.canTransitionTo(status))
        .as(
            "%s should not transition to itself; a no-op transition would still bump the "
                + "incident version and silently invalidate pending approvals",
            status)
        .isFalse();
  }

  @Nested
  @DisplayName("terminal statuses")
  class TerminalStatuses {

    @ParameterizedTest
    @EnumSource(
        value = IncidentStatus.class,
        names = {"RESOLVED", "REJECTED", "FAILED", "CANCELLED"})
    @DisplayName("admit no further transitions")
    void areTerminal(IncidentStatus status) {
      assertThat(status.isTerminal()).isTrue();
      assertThat(status.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
        value = IncidentStatus.class,
        names = {"RESOLVED", "REJECTED", "FAILED", "CANCELLED"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("everything else is non-terminal")
    void othersAreNotTerminal(IncidentStatus status) {
      assertThat(status.isTerminal()).isFalse();
    }
  }

  @Nested
  @DisplayName("execution guard")
  class ExecutionGuard {

    @Test
    @DisplayName("only REMEDIATING permits an action to execute")
    void onlyRemediatingPermitsExecution() {
      for (IncidentStatus status : IncidentStatus.values()) {
        assertThat(status.permitsActionExecution())
            .as("%s", status)
            .isEqualTo(status == IncidentStatus.REMEDIATING);
      }
    }

    @Test
    @DisplayName("an incident cannot reach REMEDIATING without passing through AWAITING_APPROVAL")
    void remediatingIsOnlyReachableViaApproval() {
      List<IncidentStatus> predecessors =
          java.util.Arrays.stream(IncidentStatus.values())
              .filter(s -> s.canTransitionTo(IncidentStatus.REMEDIATING))
              .toList();

      assertThat(predecessors)
          .as(
              "REMEDIATING must have exactly one entry point, and it must be the approval gate; "
                  + "any other predecessor would be a route to executing an unapproved action")
          .containsExactly(IncidentStatus.AWAITING_APPROVAL);
    }
  }

  @Nested
  @DisplayName("aggregate transitions")
  class AggregateTransitions {

    private Incident received() {
      return Incident.open(
          IncidentId.newId(),
          "Elevated 5xx on checkout",
          new ServiceRef("checkout", "demo"),
          Severity.SEV2,
          T0);
    }

    @Test
    @DisplayName("a legal transition increments the version")
    void legalTransitionIncrementsVersion() {
      Incident incident = received();
      assertThat(incident.version()).isZero();

      Incident moved = incident.transitionTo(IncidentStatus.INVESTIGATING, T0.plusSeconds(5));

      assertThat(moved.version()).isEqualTo(1L);
      assertThat(moved.status()).isEqualTo(IncidentStatus.INVESTIGATING);
      assertThat(moved.receivedAt()).isEqualTo(T0);
      assertThat(moved.updatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    @DisplayName("the original instance is untouched, so a rejected write cannot leave a mutation")
    void transitionDoesNotMutateSource() {
      Incident incident = received();
      incident.transitionTo(IncidentStatus.INVESTIGATING, T0.plusSeconds(5));

      assertThat(incident.status()).isEqualTo(IncidentStatus.RECEIVED);
      assertThat(incident.version()).isZero();
    }

    @Test
    @DisplayName("an illegal transition throws and names what was allowed")
    void illegalTransitionThrows() {
      Incident incident = received();

      assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.REMEDIATING, T0))
          .isInstanceOf(IllegalTransitionException.class)
          .hasMessageContaining("RECEIVED")
          .hasMessageContaining("REMEDIATING")
          .hasMessageContaining("INVESTIGATING");
    }

    @Test
    @DisplayName("closing records a note and lands on a terminal status")
    void closeRecordsNote() {
      Incident closed =
          received()
              .transitionTo(IncidentStatus.INVESTIGATING, T0)
              .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, T0)
              .close(
                  IncidentStatus.RESOLVED, "No actionable incident: alarm threshold too tight", T0);

      assertThat(closed.status()).isEqualTo(IncidentStatus.RESOLVED);
      assertThat(closed.isTerminal()).isTrue();
      assertThat(closed.closingNote())
          .contains("No actionable incident: alarm threshold too tight");
    }

    @Test
    @DisplayName("closing to a non-terminal status is refused")
    void closeRejectsNonTerminalStatus() {
      Incident incident = received();

      assertThatThrownBy(() -> incident.close(IncidentStatus.INVESTIGATING, "note", T0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a terminal status");
    }

    @Test
    @DisplayName("updating the summary bumps the version, invalidating pending approvals")
    void summaryChangeBumpsVersion() {
      Incident incident = received();

      Incident updated = incident.withSummary("Latency traced to deploy 41", T0.plusSeconds(60));

      assertThat(updated.version())
          .as("a material change must move the version, because approvals are bound to it")
          .isEqualTo(1L);
      assertThat(updated.status()).isEqualTo(IncidentStatus.RECEIVED);
    }
  }
}
