package com.lapczynski.commander.persistence.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.agent.IncidentAgentFactory;
import com.lapczynski.commander.adk.agent.PolicyGateAgent;
import com.lapczynski.commander.adk.agent.SpecialistAgents;
import com.lapczynski.commander.adk.approval.RemediationTool;
import com.lapczynski.commander.adk.model.ScriptedDemoLlm;
import com.lapczynski.commander.adk.tools.ChangeTools;
import com.lapczynski.commander.adk.tools.InvestigationTools;
import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.persistence.PostgresIntegrationTest;
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole pipeline, on the durable session service.
 *
 * <p>{@code ScriptedPipelineTest} runs the same composition against ADK's in-memory session
 * service, and passes. This one exists because that is not the same test: four specialists run
 * concurrently, each appending events and state to a shared session, and with an in-memory map that
 * is a method call while with PostgreSQL it is four concurrent transactions writing the same row.
 *
 * <p>The property under test is unglamorous and load-bearing: <strong>every state key a later stage
 * interpolates into its instruction is visible by the time that stage runs.</strong> A key that
 * arrives late does not degrade the investigation — ADK throws while rendering the instruction, and
 * the whole pipeline fails before it reaches the policy gate, which looks from the outside like a
 * failed investigation rather than a lost write.
 */
@DisplayName("The assembled pipeline, on PostgreSQL sessions")
class DurablePipelineIntegrationTest extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("every evidence key survives four concurrent specialists writing the same session")
  void concurrentBranchesAllLandBeforeTheNextStage() {
    List<Event> events = run("durable-pipeline-1");

    Map<String, Object> state = new LinkedHashMap<>();
    events.forEach(event -> state.putAll(event.actions().stateDelta()));

    assertThat(state)
        .as("a missing evidence key fails the pipeline at the next instruction, not at the gate")
        .containsKeys(
            SpecialistAgents.KEY_METRICS,
            SpecialistAgents.KEY_LOGS,
            SpecialistAgents.KEY_ECS,
            SpecialistAgents.KEY_CHANGES);

    assertThat(state)
        .as("the run has to reach the deterministic gate")
        .containsKey(PolicyGateAgent.KEY_DECISION);
  }

  @Test
  @DisplayName("the session row ends up holding what the run produced")
  void stateIsDurable() {
    run("durable-pipeline-2");

    String stored =
        jdbc.sql("SELECT state::text FROM adk_sessions WHERE session_id = :id")
            .param("id", "durable-pipeline-2")
            .query(String.class)
            .single();

    // Read back from the row rather than from the objects, because the point of this service is
    // that a different process can pick the session up. Anything held only in memory would pass an
    // assertion on the live object and be gone after a restart.
    assertThat(stored)
        .contains(SpecialistAgents.KEY_METRICS)
        .contains(SpecialistAgents.KEY_LOGS)
        .contains(SpecialistAgents.KEY_ECS)
        .contains(SpecialistAgents.KEY_CHANGES);
  }

  private List<Event> run(String sessionId) {
    SimulatedSignalSource signals =
        new SimulatedSignalSource(
            new ScenarioRun(new ScenarioLibrary().require("latency-after-bad-deployment"), NOW));

    PolicyEngine engine =
        new PolicyEngine(PolicyConfiguration.safeDefaults("123456789012", "eu-west-1", "demo"));

    Map<String, String> tags =
        Map.of("Project", "aws-incident-response-commander", "Environment", "demo");

    Runner runner =
        Runner.builder()
            .app(
                IncidentAgentFactory.resumableApp(
                    IncidentAgentFactory.incidentPipeline(
                        new ScriptedDemoLlm(),
                        new InvestigationTools(signals, signals, signals, signals, FIXED),
                        new ChangeTools(signals, signals, FIXED),
                        engine,
                        tags,
                        new RemediationTool(
                            engine, new NoOpIdempotency(), tags, action -> "nothing was executed"),
                        Schedulers.io())))
            .sessionService(new PostgresSessionService(jdbc, transactions, Schedulers.io()))
            .build();

    runner
        .sessionService()
        .createSession(
            IncidentAgentFactory.APP_NAME,
            "incident-commander",
            new LinkedHashMap<>(Map.of("incident_id", "abc", "affected_service", "checkout")),
            sessionId)
        .blockingGet();

    return runner
        .runAsync(
            "incident-commander",
            sessionId,
            Content.fromParts(Part.fromText("Alert for checkout: p99 latency tripled")),
            RunConfig.builder()
                .maxLlmCalls(IncidentAgentFactory.MAX_LLM_CALLS_PER_INVESTIGATION)
                .build())
        .toList()
        .blockingGet();
  }

  /** Claims each fingerprint once. Idempotency has its own tests; this one is about state. */
  private static final class NoOpIdempotency implements IdempotencyStore {
    private final Set<String> claimed = new HashSet<>();

    @Override
    public boolean claim(ActionFingerprint fingerprint, IncidentId incidentId) {
      return claimed.add(fingerprint.hex());
    }

    @Override
    public void complete(ActionFingerprint fingerprint, Outcome outcome, String result) {
      // Nothing to record.
    }

    @Override
    public Optional<Record> find(ActionFingerprint fingerprint) {
      return Optional.empty();
    }
  }
}
