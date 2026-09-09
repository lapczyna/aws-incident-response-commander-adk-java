package com.lapczynski.commander.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.ReportRepository;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import com.lapczynski.commander.domain.report.IncidentReport;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The storage the incident report is assembled from.
 *
 * <p>Against real PostgreSQL because the interesting parts are all database behaviour: the {@code
 * UUID[]} columns, the generated sequence that makes citation numbering stable, the four-valued
 * outcome constraint added by V4, and the one-verification-per-incident index.
 */
class ReportingIntegrationTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

  @Autowired private EvidenceRepository evidence;
  @Autowired private ExecutionRepository executions;
  @Autowired private VerificationRepository verifications;
  @Autowired private ReportRepository reports;
  @Autowired private IncidentRepository incidents;
  @Autowired private JdbcClient jdbc;

  private IncidentId incidentId;

  @BeforeEach
  void setUp() {
    truncateIncidents(jdbc);

    Incident incident =
        Incident.open(
            IncidentId.newId(),
            "Checkout latency above objective",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            T0);
    incidents.create(incident, Actor.SYSTEM);
    incidentId = incident.id();
  }

  @Nested
  @DisplayName("evidence")
  class EvidenceStorage {

    @Test
    @DisplayName("an observation round-trips intact")
    void roundTrips() {
      Evidence original = observation("latency stepped up", EvidenceSource.CLOUDWATCH_METRICS);

      evidence.save(original);

      assertThat(evidence.findByIncident(incidentId)).containsExactly(original);
    }

    @Test
    @DisplayName("reads come back in insertion order, not timestamp order")
    void stableOrdering() {
      // Citation numbers are assigned by position, so this ordering is what keeps [E2] in a
      // printed report pointing at the same observation tomorrow. Timestamps tie routinely:
      // four specialists running in parallel record within the same millisecond.
      Evidence first = observation("first", EvidenceSource.CLOUDWATCH_METRICS);
      Evidence second = observation("second", EvidenceSource.CLOUDWATCH_LOGS);
      Evidence third = observation("third", EvidenceSource.ECS_STATE);

      evidence.save(first);
      evidence.save(second);
      evidence.save(third);

      assertThat(evidence.findByIncident(incidentId))
          .extracting(Evidence::summary)
          .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("untrusted content is flagged in the stored row, not only computed on read")
    void untrustedIsPersisted() {
      evidence.save(observation("a log line", EvidenceSource.CLOUDWATCH_LOGS));
      evidence.save(observation("a datapoint", EvidenceSource.CLOUDWATCH_METRICS));

      List<Boolean> flags =
          jdbc.sql("SELECT untrusted FROM evidence WHERE incident_id = :id ORDER BY sequence")
              .param("id", incidentId.value())
              .query(Boolean.class)
              .list();

      assertThat(flags).containsExactly(true, false);
    }

    @Test
    @DisplayName("a gap round-trips with its reason")
    void gapRoundTrips() {
      EvidenceGap gap =
          new EvidenceGap(
              incidentId,
              EvidenceSource.CLOUDTRAIL_CHANGES,
              "change_investigator",
              EvidenceGap.Reason.ACCESS_DENIED,
              "CloudTrail lookup was refused",
              T0);

      evidence.saveGap(gap);

      assertThat(evidence.findGapsByIncident(incidentId)).containsExactly(gap);
    }

    @Test
    @DisplayName("gaps are stored separately from observations")
    void gapsAreNotObservations() {
      // "The logs showed nothing" and "the logs could not be read" must never merge into one
      // list, because a report that lost the difference would present an unread source as a
      // source that was read and found empty.
      evidence.save(observation("a datapoint", EvidenceSource.CLOUDWATCH_METRICS));
      evidence.saveGap(
          new EvidenceGap(
              incidentId,
              EvidenceSource.CLOUDWATCH_LOGS,
              "logs_investigator",
              EvidenceGap.Reason.TIMEOUT,
              "log query timed out",
              T0));

      assertThat(evidence.findByIncident(incidentId)).hasSize(1);
      assertThat(evidence.findGapsByIncident(incidentId)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("executions")
  class ExecutionStorage {

    @Test
    @DisplayName("an execution round-trips, including its dry-run flag")
    void roundTrips() {
      ExecutedAction action = execution(true, ExecutedAction.Outcome.SUCCEEDED, T0);

      executions.record(action);

      assertThat(executions.findByIncident(incidentId)).containsExactly(action);
      assertThat(executions.findLatestForIncident(incidentId)).contains(action);
    }

    @Test
    @DisplayName("the latest execution is the most recently started one")
    void latestWins() {
      ExecutedAction first = execution(false, ExecutedAction.Outcome.FAILED, T0);
      ExecutedAction second =
          execution(false, ExecutedAction.Outcome.SUCCEEDED, T0.plus(Duration.ofMinutes(5)));

      executions.record(first);
      executions.record(second);

      assertThat(executions.findLatestForIncident(incidentId)).contains(second);
    }

    @Test
    @DisplayName("an execution with no resolvable approval is still recorded")
    void approvalIsOptional() {
      // Something changed infrastructure. Losing the row because its approval could not be found
      // would leave no trace of it at all, which is strictly worse than an anomalous row.
      ExecutedAction orphan =
          new ExecutedAction(
              UUID.randomUUID(),
              incidentId,
              Optional.empty(),
              fingerprint(),
              ActionType.ROLLBACK_DEPLOYMENT,
              targetArn(),
              false,
              ExecutedAction.Outcome.SUCCEEDED,
              Optional.of("Forced a new deployment"),
              T0,
              Optional.of(T0.plus(Duration.ofSeconds(20))));

      claimFingerprint(orphan.fingerprint());
      executions.record(orphan);

      assertThat(executions.findLatestForIncident(incidentId)).contains(orphan);
    }
  }

  @Nested
  @DisplayName("verification")
  class VerificationStorage {

    @Test
    @DisplayName("a verification round-trips with the numbers it was judged from")
    void roundTrips() {
      Evidence observation =
          observation("post-remediation latency", EvidenceSource.CLOUDWATCH_METRICS);
      evidence.save(observation);

      RecoveryVerification original =
          new RecoveryVerification(
              incidentId,
              "TargetResponseTimeP99",
              1.15,
              0.19,
              0.4,
              RecoveryVerification.Outcome.RECOVERED,
              "Latency is back to 0.19.",
              List.of(observation.id()),
              T0);

      verifications.save(original, Optional.empty());

      assertThat(verifications.findByIncident(incidentId)).contains(original);
    }

    @Test
    @DisplayName("an unmeasured value survives as NaN rather than becoming zero")
    void nanSurvives() {
      // A zero latency reads as spectacular recovery. This is the round-trip that stops the
      // report saying it.
      Evidence observation = observation("could not measure", EvidenceSource.CLOUDWATCH_METRICS);
      evidence.save(observation);

      verifications.save(
          new RecoveryVerification(
              incidentId,
              "TargetResponseTimeP99",
              1.15,
              Double.NaN,
              0.4,
              RecoveryVerification.Outcome.INDETERMINATE,
              "The metric could not be read after remediation.",
              List.of(observation.id()),
              T0),
          Optional.empty());

      assertThat(verifications.findByIncident(incidentId))
          .get()
          .extracting(RecoveryVerification::afterValue)
          .isEqualTo(Double.NaN);
    }

    @Test
    @DisplayName("all four outcomes are accepted by the schema")
    void allOutcomesPersist() {
      // V4 replaced a boolean with a four-valued column precisely so INDETERMINATE and
      // PARTIALLY_RECOVERED have somewhere to live. This asserts the constraint agrees with the
      // enum rather than with the two values the original schema allowed.
      for (RecoveryVerification.Outcome outcome : RecoveryVerification.Outcome.values()) {
        truncateIncidents(jdbc);
        Incident incident =
            Incident.open(
                IncidentId.newId(),
                "Incident for " + outcome,
                new ServiceRef("checkout", "demo"),
                Severity.SEV2,
                T0);
        incidents.create(incident, Actor.SYSTEM);

        Evidence observation =
            new Evidence(
                EvidenceId.newId(),
                incident.id(),
                EvidenceSource.CLOUDWATCH_METRICS,
                "recovery-verifier",
                "measurement",
                "content",
                Confidence.CERTAIN,
                T0,
                Optional.empty());
        evidence.save(observation);

        verifications.save(
            new RecoveryVerification(
                incident.id(),
                "TargetResponseTimeP99",
                1.15,
                0.5,
                0.4,
                outcome,
                "Summary for " + outcome,
                List.of(observation.id()),
                T0),
            Optional.empty());

        assertThat(verifications.findByIncident(incident.id()))
            .get()
            .extracting(RecoveryVerification::outcome)
            .isEqualTo(outcome);
      }
    }

    @Test
    @DisplayName("a second verification for the same incident is refused")
    void oneVerificationPerIncident() {
      // Two verifications would raise the question of which one the incident status reflects,
      // and there is no good answer to that.
      Evidence observation = observation("measurement", EvidenceSource.CLOUDWATCH_METRICS);
      evidence.save(observation);

      RecoveryVerification first =
          new RecoveryVerification(
              incidentId,
              "TargetResponseTimeP99",
              1.15,
              0.19,
              0.4,
              RecoveryVerification.Outcome.RECOVERED,
              "Recovered.",
              List.of(observation.id()),
              T0);

      verifications.save(first, Optional.empty());

      assertThatThrownBy(() -> verifications.save(first, Optional.empty()))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Nested
  @DisplayName("reports")
  class ReportStorage {

    @Test
    @DisplayName("a report round-trips with its citation list")
    void roundTrips() {
      Evidence observation = observation("latency stepped up", EvidenceSource.CLOUDWATCH_METRICS);
      evidence.save(observation);

      IncidentReport report =
          IncidentReport.of(
              incidentId,
              "# Incident report\n\nSomething happened.",
              List.of(observation.id()),
              T0);

      reports.save(report);

      assertThat(reports.findLatest(incidentId)).contains(report);
    }

    @Test
    @DisplayName("regenerating a report keeps the earlier one")
    void reportsAreVersioned() {
      // A report written during remediation and one written after verification describe different
      // states of knowledge. Overwriting the first would erase the fact that it changed.
      IncidentReport early =
          IncidentReport.of(incidentId, "# Early\n\nStill investigating.", List.of(), T0);
      IncidentReport late =
          IncidentReport.of(
              incidentId, "# Final\n\nVerified.", List.of(), T0.plus(Duration.ofMinutes(30)));

      reports.save(early);
      reports.save(late);

      assertThat(reports.findLatest(incidentId)).contains(late);

      Integer stored =
          jdbc.sql("SELECT count(*) FROM reports WHERE incident_id = :id")
              .param("id", incidentId.value())
              .query(Integer.class)
              .single();
      assertThat(stored).isEqualTo(2);
    }
  }

  // ---------------------------------------------------------------------- fixtures

  private Evidence observation(String summary, EvidenceSource source) {
    return new Evidence(
        EvidenceId.newId(),
        incidentId,
        source,
        "metrics_investigator",
        summary,
        "content for " + summary,
        Confidence.clamped(0.9),
        T0,
        Optional.of(T0.minus(Duration.ofMinutes(1))));
  }

  private ExecutedAction execution(
      boolean dryRun, ExecutedAction.Outcome outcome, Instant startedAt) {

    ActionFingerprint fingerprint = fingerprint();
    claimFingerprint(fingerprint);

    return new ExecutedAction(
        UUID.randomUUID(),
        incidentId,
        Optional.empty(),
        fingerprint,
        ActionType.ROLLBACK_DEPLOYMENT,
        targetArn(),
        dryRun,
        outcome,
        Optional.of("detail"),
        startedAt,
        Optional.of(startedAt.plus(Duration.ofSeconds(20))));
  }

  private static String targetArn() {
    return "arn:aws:ecs:eu-west-1:123456789012:service/commander-demo/checkout";
  }

  private ActionFingerprint fingerprint() {
    ProposedAction action =
        new ProposedAction(
            ActionType.ROLLBACK_DEPLOYMENT,
            new ResourceRef(targetArn(), "123456789012", "eu-west-1", "demo", "ecs:service"),
            Map.of("nonce", UUID.randomUUID().toString()),
            "Roll checkout back one revision");
    return ActionFingerprint.of(action, incidentId, 6L);
  }

  /** executed_actions references idempotency_keys, mirroring claim-before-act in production. */
  private void claimFingerprint(ActionFingerprint fingerprint) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (fingerprint, incident_id, first_seen_at, outcome)
            VALUES (:fingerprint, :incidentId, :now, 'SUCCEEDED')
            ON CONFLICT (fingerprint) DO NOTHING
            """)
        .param("fingerprint", fingerprint.hex())
        .param("incidentId", incidentId.value())
        .param("now", java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC))
        .update();
  }
}
