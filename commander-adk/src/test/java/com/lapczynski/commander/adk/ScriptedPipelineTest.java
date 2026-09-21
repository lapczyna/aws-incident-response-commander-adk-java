package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.agent.DiagnosisAgents;
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
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pipeline the deployed application runs, driven by the model the deployed application runs.
 *
 * <p>Every other agent test in this module scripts {@code FakeLlm} turn by turn, which is the right
 * tool for asserting that a specific model response produces a specific outcome. This one asks a
 * different question: whether the composition in {@code AgentRuntimeConfiguration} — four
 * specialists, a critique loop, a planner, the gate, and an executor inside the gate — actually
 * runs end to end against the model that ships with the {@code fake} profile.
 *
 * <p>It exists because it did not. Every stage passed its own test while the assembled pipeline
 * failed on the first hypothesis, and nothing in the suite would have noticed.
 */
@DisplayName("The assembled pipeline, with the shipped demo model")
class ScriptedPipelineTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  @DisplayName("reaches a policy decision, having written every evidence key the later stages read")
  void reachesAPolicyDecision() {
    List<Event> events = run();

    Map<String, Object> state = new LinkedHashMap<>();
    events.forEach(event -> state.putAll(event.actions().stateDelta()));

    // The four specialists each write a key that the hypothesis, critique and planner instructions
    // interpolate. A missing one is not a degraded investigation — ADK throws while rendering the
    // instruction, and the whole pipeline fails before it reaches the gate.
    assertThat(state)
        .as("every evidence key the downstream instructions reference must be present")
        .containsKeys(
            SpecialistAgents.KEY_METRICS,
            SpecialistAgents.KEY_LOGS,
            SpecialistAgents.KEY_ECS,
            SpecialistAgents.KEY_CHANGES);

    assertThat(state).containsKey(DiagnosisAgents.KEY_HYPOTHESIS);
    assertThat(state)
        .as("the run has to reach the gate; anything earlier means a stage failed silently")
        .containsKey(PolicyGateAgent.KEY_DECISION);
  }

  @Test
  @DisplayName("denies the shipped proposal, because the shipped defaults allowlist nothing")
  void deniesUnderSafeDefaults() {
    List<Event> events = run();

    Object decision =
        events.stream()
            .map(event -> event.actions().stateDelta().get(PolicyGateAgent.KEY_DECISION))
            .filter(java.util.Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);

    assertThat(decision).isEqualTo("DENIED");
    assertThat(events.stream().map(Event::author))
        .as("nothing downstream of a denial may run")
        .doesNotContain("remediation_executor");
  }

  private static List<Event> run() {
    SimulatedSignalSource signals =
        new SimulatedSignalSource(
            new ScenarioRun(new ScenarioLibrary().require("latency-after-bad-deployment"), NOW));

    InvestigationTools tools = new InvestigationTools(signals, signals, signals, signals, FIXED);
    ChangeTools changes = new ChangeTools(signals, signals, FIXED);

    PolicyEngine engine =
        new PolicyEngine(PolicyConfiguration.safeDefaults("123456789012", "eu-west-1", "demo"));

    RemediationTool remediation =
        new RemediationTool(
            engine,
            new NoOpIdempotency(),
            Map.of("Project", "aws-incident-response-commander", "Environment", "demo"),
            action -> "nothing was executed");

    Runner runner =
        Runner.builder()
            .app(
                IncidentAgentFactory.resumableApp(
                    IncidentAgentFactory.incidentPipeline(
                        new ScriptedDemoLlm(),
                        tools,
                        changes,
                        engine,
                        Map.of("Project", "aws-incident-response-commander", "Environment", "demo"),
                        remediation,
                        Schedulers.io()),
                    List.of(
                        new com.lapczynski.commander.adk.plugin.InvestigationBudgetPlugin(
                            16, java.time.Duration.ofMinutes(5)))))
            .sessionService(new InMemorySessionService())
            .build();

    runner
        .sessionService()
        .createSession(
            IncidentAgentFactory.APP_NAME,
            "operator",
            new LinkedHashMap<>(Map.of("incident_id", "abc", "affected_service", "checkout")),
            "s1")
        .blockingGet();

    return runner
        .runAsync(
            "operator",
            "s1",
            Content.fromParts(Part.fromText("Alert for checkout: p99 latency tripled")),
            RunConfig.builder()
                .maxLlmCalls(IncidentAgentFactory.MAX_LLM_CALLS_PER_INVESTIGATION)
                .build(),
            Map.of())
        .toList()
        .blockingGet();
  }

  /** Claims everything once, which is all this test needs; idempotency has its own tests. */
  private static final class NoOpIdempotency implements IdempotencyStore {
    private final Set<String> claimed = new java.util.HashSet<>();

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
