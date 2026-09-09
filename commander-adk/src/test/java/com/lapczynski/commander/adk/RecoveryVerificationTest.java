package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.agent.IncidentAgentFactory;
import com.lapczynski.commander.adk.agent.RecoveryVerifierAgent;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.verification.RecoveryVerifier;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.remediation.ExecutedAction;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import com.lapczynski.commander.testing.FakeLlm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The closing stage: measure, then describe.
 *
 * <p>The property under test is that the verdict is produced by code and the model only writes
 * about it. Scenario 8 is the case that matters — a convincing diagnosis, a rollback that
 * completed, and a service that stayed broken — and the system must reach {@code FAILED} rather
 * than reporting success because it did the thing it said it would do.
 */
class RecoveryVerificationTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ScenarioLibrary LIBRARY = new ScenarioLibrary();

  private static final String APP = IncidentAgentFactory.APP_NAME;
  private static final String USER = "operator";

  @Nested
  @DisplayName("when the fix did not work")
  class FailedVerification {

    @Test
    @DisplayName("scenario 8 reaches FAILED rather than RESOLVED")
    void scenarioEightFails() {
      // Latency in this scenario never steps back down: the rollback addressed a deployment that
      // was not the cause. The whole point of verification is to notice.
      Fixture fixture = fixture("remediation-fails-verification");

      List<Event> events = run(fixture);

      assertThat(fixture.incident().status()).isEqualTo(IncidentStatus.FAILED);
      assertThat(state(events, RecoveryVerifierAgent.KEY_OUTCOME))
          .isEqualTo(RecoveryVerification.Outcome.NOT_RECOVERED.name());
      assertThat(state(events, RecoveryVerifierAgent.KEY_RESOLVED)).isEqualTo(false);
    }

    @Test
    @DisplayName("the verdict is emitted before the narrator is asked to write about it")
    void verdictPrecedesNarrative() {
      // Order is the safety property. A narrative written first would read like a summary of a
      // successful remediation, and it is what a human actually reads.
      Fixture fixture = fixture("remediation-fails-verification");

      List<Event> events = run(fixture);

      List<String> authors = events.stream().map(Event::author).toList();
      assertThat(authors).contains("recovery_verifier", "report_narrator");
      assertThat(authors.indexOf("recovery_verifier"))
          .isLessThan(authors.indexOf("report_narrator"));
    }

    @Test
    @DisplayName("the narrator is told the measured result rather than asked for one")
    void narratorReceivesTheVerdict() {
      Fixture fixture = fixture("remediation-fails-verification");

      run(fixture);

      // The verdict reaches the prompt through session state, so the model is describing a
      // conclusion it did not reach.
      assertThat(fixture.model().allPromptText()).contains("still");
      assertThat(fixture.model().allPromptText()).contains("recovery threshold");
    }
  }

  @Nested
  @DisplayName("when the fix worked")
  class SuccessfulVerification {

    @Test
    @DisplayName("a recovered service resolves the incident")
    void resolves() {
      // Scenario 1's latency does step back down within the observation window used here, so this
      // is the same pipeline reaching the opposite verdict from the same code.
      Fixture fixture = fixture("latency-after-bad-deployment", 0.05, 5.0);

      run(fixture);

      assertThat(fixture.incident().status()).isEqualTo(IncidentStatus.RESOLVED);
    }
  }

  @Nested
  @DisplayName("guards")
  class Guards {

    @Test
    @DisplayName("missing verification criteria yield INDETERMINATE, never a resolution")
    void missingCriteria() {
      // A stage that could not read what it was supposed to measure must not conclude anything.
      Fixture fixture = fixture("remediation-fails-verification");
      fixture.initialState().remove(RecoveryVerifierAgent.KEY_THRESHOLD);

      List<Event> events = run(fixture);

      assertThat(state(events, RecoveryVerifierAgent.KEY_OUTCOME))
          .isEqualTo(RecoveryVerification.Outcome.INDETERMINATE.name());
      assertThat(state(events, RecoveryVerifierAgent.KEY_RESOLVED)).isEqualTo(false);

      // And nothing was closed on the strength of it.
      assertThat(fixture.incident().status()).isEqualTo(IncidentStatus.VERIFYING);
    }

    @Test
    @DisplayName("the pipeline still produces a narrative when verification is indeterminate")
    void narrativeSurvivesIndeterminateVerdict() {
      Fixture fixture = fixture("remediation-fails-verification");
      fixture.initialState().remove(RecoveryVerifierAgent.KEY_METRIC);

      List<Event> events = run(fixture);

      assertThat(events.stream().map(Event::author)).contains("report_narrator");
    }
  }

  // ---------------------------------------------------------------------- harness

  private record Fixture(
      Runner runner,
      FakeLlm model,
      Session session,
      Map<String, Object> initialState,
      InMemoryIncidents incidents) {

    Incident incident() {
      return incidents.stored;
    }
  }

  private static Fixture fixture(String scenarioId) {
    return fixture(scenarioId, 1.15, 0.4);
  }

  private static Fixture fixture(String scenarioId, double before, double threshold) {
    SimulatedSignalSource simulator =
        new SimulatedSignalSource(new ScenarioRun(LIBRARY.require(scenarioId), NOW));

    InMemoryIncidents incidents = new InMemoryIncidents(verifyingIncident());
    RecoveryVerifier verifier =
        new RecoveryVerifier(
            (MetricsPort) simulator,
            new CollectingEvidence(),
            new OneExecution(incidents.stored.id()),
            new CollectingVerifications(),
            incidents,
            new CollectingAudit(),
            FIXED);

    FakeLlm model = FakeLlm.alwaysSaying("### What happened\n\nLatency rose [E1].");

    App app =
        App.builder()
            .name(APP)
            .rootAgent(IncidentAgentFactory.verificationPipeline(model, verifier))
            .build();

    InMemorySessionService sessions = new InMemorySessionService();

    Map<String, Object> initialState = new HashMap<>();
    initialState.put(RecoveryVerifierAgent.KEY_INCIDENT_ID, incidents.stored.id().toString());
    initialState.put(RecoveryVerifierAgent.KEY_SERVICE, "checkout");
    initialState.put(RecoveryVerifierAgent.KEY_METRIC, "TargetResponseTimeP99");
    initialState.put(RecoveryVerifierAgent.KEY_BEFORE, before);
    initialState.put(RecoveryVerifierAgent.KEY_THRESHOLD, threshold);
    initialState.put("investigation_summary", "Latency stepped up alongside a deployment.");
    initialState.put("hypothesis", "The deployment caused the latency step.");
    initialState.put("citable_evidence", "[E1] CLOUDWATCH_METRICS — latency stepped up");

    Session session = sessions.createSession(APP, USER, initialState, null).blockingGet();

    return new Fixture(
        Runner.builder().app(app).sessionService(sessions).build(),
        model,
        session,
        initialState,
        incidents);
  }

  private static List<Event> run(Fixture fixture) {
    // The session was created before a test could adjust initialState, so it is recreated here to
    // pick up any removal a guard test made.
    Session session =
        fixture
            .runner()
            .sessionService()
            .createSession(APP, USER, fixture.initialState(), null)
            .blockingGet();

    return fixture
        .runner()
        .runAsync(
            USER,
            session.id(),
            Content.fromParts(Part.fromText("Verify recovery.")),
            RunConfig.builder()
                .maxLlmCalls(IncidentAgentFactory.MAX_LLM_CALLS_PER_INVESTIGATION)
                .build())
        .toList()
        .blockingGet();
  }

  private static Object state(List<Event> events, String key) {
    Object value = null;
    for (Event event : events) {
      Object candidate = event.actions().stateDelta().get(key);
      if (candidate != null) {
        value = candidate;
      }
    }
    return value;
  }

  private static Incident verifyingIncident() {
    return Incident.open(
            IncidentId.newId(),
            "Checkout latency above objective",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            NOW.minus(Duration.ofHours(1)))
        .transitionTo(IncidentStatus.INVESTIGATING, NOW.minus(Duration.ofMinutes(50)))
        .transitionTo(IncidentStatus.FORMING_HYPOTHESIS, NOW.minus(Duration.ofMinutes(45)))
        .transitionTo(IncidentStatus.PLANNING_REMEDIATION, NOW.minus(Duration.ofMinutes(40)))
        .transitionTo(IncidentStatus.AWAITING_APPROVAL, NOW.minus(Duration.ofMinutes(35)))
        .transitionTo(IncidentStatus.REMEDIATING, NOW.minus(Duration.ofMinutes(20)))
        .transitionTo(IncidentStatus.VERIFYING, NOW.minus(Duration.ofMinutes(15)));
  }

  // ------------------------------------------------------------------ in-memory ports

  /** Hand-written rather than mocked: the test asserts on the state the incident ends in. */
  private static final class InMemoryIncidents implements IncidentRepository {

    private Incident stored;

    InMemoryIncidents(Incident initial) {
      this.stored = initial;
    }

    @Override
    public void create(Incident incident, Actor openedBy) {
      stored = incident;
    }

    @Override
    public Optional<Incident> findById(IncidentId id) {
      return stored.id().equals(id) ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public void update(Incident incident, long expectedVersion, Actor actor, String reason) {
      if (stored.version() != expectedVersion) {
        throw new IllegalStateException("optimistic lock failure in fixture");
      }
      stored = incident;
    }

    @Override
    public List<Incident> findOpen(int limit) {
      return List.of(stored);
    }

    @Override
    public List<Incident> findAwaitingApproval() {
      return List.of();
    }
  }

  private static final class OneExecution implements ExecutionRepository {

    private final ExecutedAction action;

    OneExecution(IncidentId incidentId) {
      this.action =
          new ExecutedAction(
              UUID.randomUUID(),
              incidentId,
              Optional.empty(),
              com.lapczynski.commander.domain.approval.ActionFingerprint.fromHex("a".repeat(64)),
              com.lapczynski.commander.domain.remediation.ActionType.ROLLBACK_DEPLOYMENT,
              "arn:aws:ecs:eu-west-1:123456789012:service/commander-demo/checkout",
              false,
              ExecutedAction.Outcome.SUCCEEDED,
              Optional.of("Forced a new deployment"),
              NOW.minus(Duration.ofMinutes(20)),
              // Long enough ago that the settle and observation windows are both complete.
              Optional.of(NOW.minus(Duration.ofMinutes(18))));
    }

    @Override
    public void record(ExecutedAction executed) {
      throw new UnsupportedOperationException("the fixture records nothing");
    }

    @Override
    public List<ExecutedAction> findByIncident(IncidentId incidentId) {
      return List.of(action);
    }

    @Override
    public Optional<ExecutedAction> findLatestForIncident(IncidentId incidentId) {
      return Optional.of(action);
    }
  }

  private static final class CollectingEvidence implements EvidenceRepository {

    private final List<Evidence> saved = new ArrayList<>();
    private final List<EvidenceGap> gaps = new ArrayList<>();

    @Override
    public void save(Evidence evidence) {
      saved.add(evidence);
    }

    @Override
    public void saveGap(EvidenceGap gap) {
      gaps.add(gap);
    }

    @Override
    public List<Evidence> findByIncident(IncidentId incidentId) {
      return List.copyOf(saved);
    }

    @Override
    public List<EvidenceGap> findGapsByIncident(IncidentId incidentId) {
      return List.copyOf(gaps);
    }
  }

  private static final class CollectingVerifications implements VerificationRepository {

    private RecoveryVerification saved;

    @Override
    public void save(RecoveryVerification verification, Optional<UUID> executedActionId) {
      saved = verification;
    }

    @Override
    public Optional<RecoveryVerification> findByIncident(IncidentId incidentId) {
      return Optional.ofNullable(saved);
    }
  }

  private static final class CollectingAudit implements AuditLog {

    private final List<AuditEvent> entries = new ArrayList<>();

    @Override
    public void append(AuditEvent event) {
      entries.add(event);
    }

    @Override
    public List<AuditEvent> findByIncident(IncidentId incidentId) {
      return List.copyOf(entries);
    }

    @Override
    public List<AuditEvent> findSecurityRelevant(int limit) {
      return List.copyOf(entries);
    }
  }
}
