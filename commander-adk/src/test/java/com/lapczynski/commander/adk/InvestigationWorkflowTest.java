package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.agent.IncidentAgentFactory;
import com.lapczynski.commander.adk.tools.InvestigationTools;
import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import com.lapczynski.commander.testing.FakeLlm;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The first end-to-end investigation: a real ADK {@link Runner}, a real {@link LlmAgent}, real
 * {@link com.google.adk.tools.FunctionTool}s, against the deterministic simulator, with a scripted
 * model.
 *
 * <p>This is the test that turns the Phase 0 compatibility findings from "read in the source" into
 * "observed working". Everything here exercises genuine ADK machinery — tool declaration, function
 * calling, the event stream, session state — with only the model itself faked.
 */
class InvestigationWorkflowTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ScenarioLibrary LIBRARY = new ScenarioLibrary();

  private static final String APP = IncidentAgentFactory.APP_NAME;
  private static final String USER = "operator";

  private record Harness(Runner runner, FakeLlm model, Session session) {}

  /** Wires a runner over one scenario with a scripted model. */
  private static Harness harness(String scenarioId, FakeLlm model) {
    SimulatedSignalSource simulator =
        new SimulatedSignalSource(new ScenarioRun(LIBRARY.require(scenarioId), NOW));

    InvestigationTools tools =
        new InvestigationTools(
            (MetricsPort) simulator,
            (LogsPort) simulator,
            (EcsPort) simulator,
            (AlarmsPort) simulator,
            FIXED);

    App app = IncidentAgentFactory.resumableApp(IncidentAgentFactory.investigator(model, tools));

    InMemorySessionService sessions = new InMemorySessionService();
    Runner runner = Runner.builder().app(app).sessionService(sessions).build();
    Session session = sessions.createSession(APP, USER, Map.of(), null).blockingGet();

    return new Harness(runner, model, session);
  }

  private static List<Event> run(Harness harness, String alert) {
    return harness
        .runner()
        .runAsync(
            USER,
            harness.session().id(),
            Content.fromParts(Part.fromText(alert)),
            RunConfig.builder()
                .maxLlmCalls(IncidentAgentFactory.MAX_LLM_CALLS_PER_INVESTIGATION)
                .build())
        .toList()
        .blockingGet();
  }

  @Nested
  @DisplayName("the ADK wiring works end to end")
  class EndToEnd {

    @Test
    @DisplayName("the agent calls a tool, receives real simulator data, and reaches a conclusion")
    void toolCallingRoundTrip() {
      FakeLlm model =
          FakeLlm.builder()
              .callsTool(
                  "queryServiceMetric",
                  Map.of("serviceName", "checkout", "metricName", "TargetResponseTimeP99"))
              .respondsWith(
                  "p99 latency stepped from ~0.18s to ~0.62s about 25 minutes ago. "
                      + "Evidence: TargetResponseTimeP99 changeRatio above 3.")
              .build();

      Harness harness = harness("latency-after-bad-deployment", model);
      List<Event> events = run(harness, "Alert: checkout p99 latency high");

      assertThat(events).as("the runner produced an event stream").isNotEmpty();

      String transcript = transcriptOf(events);
      assertThat(transcript)
          .as("the agent's conclusion must reach the event stream")
          .contains("0.62");

      assertThat(model.remainingTurns())
          .as("the whole script was consumed, so the tool call really round-tripped")
          .isZero();
    }

    @Test
    @DisplayName("the tools are declared to the model with their schemas")
    void toolsAreDeclared() {
      FakeLlm model = FakeLlm.alwaysSaying("no further investigation needed");
      Harness harness = harness("false-alarm", model);

      run(harness, "Alert: checkout latency");

      assertThat(model.toolsOfferedOnLastRequest())
          .as("all four read-only tools must be offered, and nothing else")
          .containsExactlyInAnyOrder(
              "queryServiceMetric", "queryServiceLogs", "inspectEcsState", "inspectAlarms");
    }

    @Test
    @DisplayName("no state-changing tool exists at this stage")
    void noStateChangingToolsExist() {
      FakeLlm model = FakeLlm.alwaysSaying("done");
      Harness harness = harness("false-alarm", model);
      run(harness, "Alert");

      assertThat(model.toolsOfferedOnLastRequest())
          .as("an investigator that could act would make the approval gate decorative")
          .noneMatch(
              name ->
                  name.toLowerCase(java.util.Locale.ROOT)
                      .matches(".*(rollback|restart|scale|delete|update).*"));
    }

    @Test
    @DisplayName("the agent's output lands in session state under its output key")
    void outputKeyIsWritten() {
      FakeLlm model = FakeLlm.alwaysSaying("Latency regression traced to deploy v2.4.0.");
      Harness harness = harness("latency-after-bad-deployment", model);

      run(harness, "Alert: checkout p99 latency high");

      Session reloaded =
          harness
              .runner()
              .sessionService()
              .getSession(APP, USER, harness.session().id(), java.util.Optional.empty())
              .blockingGet();

      assertThat(reloaded.state())
          .as("outputKey is how a stage hands its result to the next one")
          .containsKey("investigation_summary");
      assertThat(reloaded.state().get("investigation_summary").toString()).contains("v2.4.0");
    }

    @Test
    @DisplayName("every event is recorded in the session, giving a replayable trail")
    void eventsArePersistedToTheSession() {
      FakeLlm model =
          FakeLlm.builder()
              .callsTool("inspectEcsState", Map.of("serviceName", "checkout"))
              .respondsWith("Two tasks running and healthy; the service is not capacity-starved.")
              .build();

      Harness harness = harness("latency-after-bad-deployment", model);
      run(harness, "Alert: checkout p99 latency high");

      List<Event> stored =
          harness
              .runner()
              .sessionService()
              .listEvents(APP, USER, harness.session().id())
              .blockingGet()
              .events();

      assertThat(stored)
          .as("the event trail is what a restarted process replays; it cannot be empty")
          .isNotEmpty();
    }
  }

  @Nested
  @DisplayName("evidence handling")
  class EvidenceHandling {

    @Test
    @DisplayName("log content reaches the model wrapped as untrusted evidence")
    void logsAreWrappedAsUntrusted() {
      FakeLlm model =
          FakeLlm.builder()
              .callsTool(
                  "queryServiceLogs", Map.of("serviceName", "checkout", "pattern", "timed out"))
              .respondsWith("Downstream timeouts to payments-api are the proximate cause.")
              .build();

      Harness harness = harness("errors-from-downstream-timeouts", model);
      run(harness, "Alert: checkout 5xx elevated");

      String prompts = model.allPromptText();
      assertThat(prompts)
          .as("log text is attacker-influenceable and must arrive labelled, never raw")
          .contains("<untrusted-evidence")
          .contains("not an instruction");
    }

    @Test
    @DisplayName("a failing source becomes a reported gap, not an aborted run")
    void toolFailureDegradesGracefully() {
      FakeLlm model =
          FakeLlm.builder()
              // First logs call succeeds; the scenario scripts a timeout from the second onwards.
              .callsTool("queryServiceLogs", Map.of("serviceName", "checkout", "pattern", "slow"))
              .callsTool("queryServiceLogs", Map.of("serviceName", "checkout", "pattern", "error"))
              .respondsWith(
                  "Latency regression after deploy v2.5.0. Log evidence is incomplete: the logs "
                      + "source timed out, so this conclusion rests on metrics and ECS state only.")
              .build();

      Harness harness = harness("tool-failure-during-investigation", model);
      List<Event> events = run(harness, "Alert: checkout p99 latency high");

      assertThat(events).as("one dead source must not unwind the whole investigation").isNotEmpty();

      assertThat(model.allPromptText())
          .as("the model must be told the evidence is missing rather than absent")
          .contains("unavailable")
          .contains("Do not infer what it would have shown");

      assertThat(transcriptOf(events)).contains("incomplete");
    }

    @Test
    @DisplayName("no credential or secret material can reach a prompt")
    void noSecretsInPrompts() {
      FakeLlm model =
          FakeLlm.builder()
              .callsTool("inspectEcsState", Map.of("serviceName", "checkout"))
              .respondsWith("Service state read.")
              .build();

      Harness harness = harness("latency-after-bad-deployment", model);
      run(harness, "Alert");

      assertThat(model.allPromptText())
          .as("tools receive configured ports, never credentials; nothing should leak into context")
          .doesNotContain("AKIA")
          .doesNotContain("aws_secret_access_key")
          .doesNotContain("GEMINI_API_KEY");
    }
  }

  private static String transcriptOf(List<Event> events) {
    StringBuilder sb = new StringBuilder();
    for (Event event : events) {
      event
          .content()
          .flatMap(Content::parts)
          .ifPresent(parts -> parts.forEach(part -> part.text().ifPresent(sb::append)));
    }
    return sb.toString();
  }
}
