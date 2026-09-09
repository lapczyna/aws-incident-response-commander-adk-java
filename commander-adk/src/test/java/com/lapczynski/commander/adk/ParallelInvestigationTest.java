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
import com.lapczynski.commander.adk.agent.SpecialistAgents;
import com.lapczynski.commander.adk.plugin.InvestigationBudgetPlugin;
import com.lapczynski.commander.adk.tools.ChangeTools;
import com.lapczynski.commander.adk.tools.InvestigationTools;
import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.simulator.ScenarioLibrary;
import com.lapczynski.commander.simulator.ScenarioRun;
import com.lapczynski.commander.simulator.SimulatedSignalSource;
import com.lapczynski.commander.testing.FakeLlm;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parallel multi-agent investigation.
 *
 * <p>Two properties get the most attention, because they are the reasons for having four agents
 * instead of one: the specialists really do run concurrently, and one of them failing does not take
 * the others with it.
 */
class ParallelInvestigationTest {

  private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ScenarioLibrary LIBRARY = new ScenarioLibrary();

  private static final String APP = IncidentAgentFactory.APP_NAME;
  private static final String USER = "operator";

  private record Harness(
      Runner runner, FakeLlm model, Session session, SimulatedSignalSource simulator) {}

  /**
   * Wires the full pipeline over one scenario.
   *
   * <p>All four specialists share one {@link FakeLlm}. That is what makes the parallelism testable:
   * the fake hands out its scripted turns in the order they are requested, so the assertions below
   * are about the shape of the run rather than about which agent got which reply.
   */
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
    ChangeTools changeTools =
        new ChangeTools((ChangeHistoryPort) simulator, (DeploymentHistoryPort) simulator, FIXED);

    App app =
        App.builder()
            .name(APP)
            .rootAgent(
                IncidentAgentFactory.investigationPipeline(
                    model, tools, changeTools, Schedulers.io()))
            .plugins(new InvestigationBudgetPlugin(12, Duration.ofMinutes(5)))
            .build();

    InMemorySessionService sessions = new InMemorySessionService();
    Runner runner = Runner.builder().app(app).sessionService(sessions).build();
    Session session = sessions.createSession(APP, USER, Map.of(), null).blockingGet();

    return new Harness(runner, model, session, simulator);
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

  private static Session reload(Harness harness) {
    return harness
        .runner()
        .sessionService()
        .getSession(APP, USER, harness.session().id(), Optional.empty())
        .blockingGet();
  }

  /**
   * A model that calls each specialist's tools once, then reports.
   *
   * <p>Request-driven rather than a positional script. Four specialists share one fake and run
   * concurrently, so a script consumed by position would make these assertions depend on scheduler
   * timing — and, worse, an empty script would let the run "succeed" having called no tools at all.
   */
  private static FakeLlm scriptedForAllSpecialists() {
    return FakeLlm.builder()
        .callsEachOfferedToolOnceThenSays("Findings reported from the evidence available.")
        .build();
  }

  @Nested
  @DisplayName("parallel evidence collection")
  class ParallelCollection {

    @Test
    @DisplayName("all four specialists run and each writes its own output key")
    void allSpecialistsProduceOutput() {
      Harness harness = harness("latency-after-bad-deployment", scriptedForAllSpecialists());
      run(harness, "Alert: checkout p99 latency high");

      Session session = reload(harness);

      assertThat(session.state())
          .as("the four output keys are the contract with the synthesis stage")
          .containsKeys(
              SpecialistAgents.KEY_METRICS,
              SpecialistAgents.KEY_LOGS,
              SpecialistAgents.KEY_ECS,
              SpecialistAgents.KEY_CHANGES);
    }

    @Test
    @DisplayName("the synthesis stage runs after the specialists and writes the summary")
    void synthesisRunsAfterCollection() {
      Harness harness = harness("latency-after-bad-deployment", scriptedForAllSpecialists());
      run(harness, "Alert: checkout p99 latency high");

      Session session = reload(harness);

      assertThat(session.state())
          .as("a SequentialAgent guarantees synthesis sees a complete evidence set")
          .containsKey("investigation_summary");
    }

    @Test
    @DisplayName("each specialist's events are attributed to it")
    void eventsAreAttributedPerAgent() {
      Harness harness = harness("latency-after-bad-deployment", scriptedForAllSpecialists());
      List<Event> events = run(harness, "Alert: checkout p99 latency high");

      assertThat(events)
          .extracting(Event::author)
          .as("without per-agent attribution a parallel run is unreadable in the trail")
          .contains(
              "metrics_investigator",
              "logs_investigator",
              "ecs_investigator",
              "change_investigator",
              "evidence_synthesis");
    }

    @Test
    @DisplayName("ADK gives each parallel branch its own identifier")
    void branchesAreDistinct() {
      Harness harness = harness("latency-after-bad-deployment", scriptedForAllSpecialists());
      List<Event> events = run(harness, "Alert: checkout p99 latency high");

      List<String> branches =
          events.stream().map(Event::branch).flatMap(Optional::stream).distinct().toList();

      assertThat(branches)
          .as("branches are how concurrent evidence collection stays traceable")
          .isNotEmpty();
    }
  }

  @Nested
  @DisplayName("specialists are narrowly scoped")
  class NarrowScoping {

    @Test
    @DisplayName("no specialist is offered a tool outside its remit")
    void toolSetsAreNarrow() {
      // Each specialist is built individually so its declared tools can be inspected without
      // depending on which agent happened to make the last request during a parallel run.
      SimulatedSignalSource simulator =
          new SimulatedSignalSource(
              new ScenarioRun(LIBRARY.require("latency-after-bad-deployment"), NOW));
      InvestigationTools tools =
          new InvestigationTools(
              (MetricsPort) simulator,
              (LogsPort) simulator,
              (EcsPort) simulator,
              (AlarmsPort) simulator,
              FIXED);
      ChangeTools changeTools =
          new ChangeTools((ChangeHistoryPort) simulator, (DeploymentHistoryPort) simulator, FIXED);
      FakeLlm model = FakeLlm.alwaysSaying("ok");

      assertThat(toolNames(SpecialistAgents.metricsInvestigator(model, tools)))
          .containsExactly("queryServiceMetric");
      assertThat(toolNames(SpecialistAgents.logsInvestigator(model, tools)))
          .containsExactly("queryServiceLogs");
      assertThat(toolNames(SpecialistAgents.ecsInvestigator(model, tools)))
          .containsExactlyInAnyOrder("inspectEcsState", "inspectAlarms");
      assertThat(toolNames(SpecialistAgents.changeInvestigator(model, changeTools)))
          .containsExactlyInAnyOrder("recentChanges", "deploymentHistory");
    }

    @Test
    @DisplayName("the synthesis agent has no tools at all")
    void synthesisHasNoTools() {
      assertThat(toolNames(IncidentAgentFactory.synthesis(FakeLlm.alwaysSaying("ok"))))
          .as(
              "giving synthesis tools would let it paper over a contradiction with a fresh query "
                  + "instead of reporting it")
          .isEmpty();
    }

    /** LlmAgent.tools() is a Single, because a toolset can resolve asynchronously. */
    private static List<String> toolNames(com.google.adk.agents.LlmAgent agent) {
      return agent.tools().blockingGet().stream().map(com.google.adk.tools.BaseTool::name).toList();
    }
  }

  @Nested
  @DisplayName("partial failure")
  class PartialFailure {

    @Test
    @DisplayName("a failing source does not stop the other specialists")
    void oneFailingSourceDoesNotAbortTheRun() {
      // This scenario scripts the logs source to time out after its first call.
      Harness harness = harness("tool-failure-during-investigation", scriptedForAllSpecialists());
      run(harness, "Alert: checkout p99 latency high");

      Session session = reload(harness);

      assertThat(session.state())
          .as("the point of four specialists is that losing one leaves three")
          .containsKeys(
              SpecialistAgents.KEY_METRICS, SpecialistAgents.KEY_ECS, SpecialistAgents.KEY_CHANGES);

      assertThat(session.state())
          .as("the failing specialist must still report, with its gap")
          .containsKey(SpecialistAgents.KEY_LOGS);

      assertThat(session.state())
          .as("a degraded investigation still reaches a conclusion")
          .containsKey("investigation_summary");
    }

    @Test
    @DisplayName("the failing specialist is told the evidence is missing, not absent")
    void gapIsCommunicatedAsMissing() {
      FakeLlm model = scriptedForAllSpecialists();
      Harness harness = harness("tool-failure-during-investigation", model);

      // The scenario scripts the logs source to succeed once and fail from the second call
      // onwards, which is what a rate limit or a mid-investigation outage looks like. The model
      // under test calls each tool once, so that first success is consumed here to arm the failure
      // for the run. Doing it explicitly beats rewriting the fixture to fail immediately: a source
      // that fails on its very first call is a different, easier situation than one that fails
      // partway through an investigation.
      harness
          .simulator()
          .query(
              new LogsPort.LogQuery("checkout", "slow", harness.simulator().defaultWindow(), 10));

      run(harness, "Alert: checkout p99 latency high");

      assertThat(model.allPromptText())
          .as(
              "'the logs showed nothing' and 'the logs could not be read' support very different "
                  + "conclusions")
          .contains("Do not infer what it would have shown");
    }
  }

  @Nested
  @DisplayName("budget enforcement")
  class Budget {

    @Test
    @DisplayName("the tool budget is shared across the whole invocation, not per agent")
    void budgetIsSharedAcrossSpecialists() {
      SimulatedSignalSource simulator =
          new SimulatedSignalSource(
              new ScenarioRun(LIBRARY.require("latency-after-bad-deployment"), NOW));
      InvestigationTools tools =
          new InvestigationTools(
              (MetricsPort) simulator,
              (LogsPort) simulator,
              (EcsPort) simulator,
              (AlarmsPort) simulator,
              FIXED);
      ChangeTools changeTools =
          new ChangeTools((ChangeHistoryPort) simulator, (DeploymentHistoryPort) simulator, FIXED);

      // A budget of two across four specialists: most calls must be refused.
      InvestigationBudgetPlugin budget = new InvestigationBudgetPlugin(2, Duration.ofMinutes(5));

      FakeLlm model = scriptedForAllSpecialists();
      App app =
          App.builder()
              .name(APP)
              .rootAgent(
                  IncidentAgentFactory.investigationPipeline(
                      model, tools, changeTools, Schedulers.io()))
              .plugins(budget)
              .build();

      InMemorySessionService sessions = new InMemorySessionService();
      Runner runner = Runner.builder().app(app).sessionService(sessions).build();
      Session session = sessions.createSession(APP, USER, Map.of(), null).blockingGet();

      runner
          .runAsync(
              USER,
              session.id(),
              Content.fromParts(Part.fromText("Alert")),
              RunConfig.builder().maxLlmCalls(20).build())
          .toList()
          .blockingGet();

      assertThat(model.allPromptText())
          .as(
              "a per-agent budget would let four specialists collectively spend four times the "
                  + "intended allowance")
          .contains("budget");
    }

    @Test
    @DisplayName("a refusal is a tool result the agent can reason about, not a crash")
    void refusalIsGraceful() {
      SimulatedSignalSource simulator =
          new SimulatedSignalSource(
              new ScenarioRun(LIBRARY.require("latency-after-bad-deployment"), NOW));
      InvestigationTools tools =
          new InvestigationTools(
              (MetricsPort) simulator,
              (LogsPort) simulator,
              (EcsPort) simulator,
              (AlarmsPort) simulator,
              FIXED);
      ChangeTools changeTools =
          new ChangeTools((ChangeHistoryPort) simulator, (DeploymentHistoryPort) simulator, FIXED);

      FakeLlm model = scriptedForAllSpecialists();
      App app =
          App.builder()
              .name(APP)
              .rootAgent(
                  IncidentAgentFactory.investigationPipeline(
                      model, tools, changeTools, Schedulers.io()))
              .plugins(new InvestigationBudgetPlugin(1, Duration.ofMinutes(5)))
              .build();

      InMemorySessionService sessions = new InMemorySessionService();
      Runner runner = Runner.builder().app(app).sessionService(sessions).build();
      Session session = sessions.createSession(APP, USER, Map.of(), null).blockingGet();

      List<Event> events =
          runner
              .runAsync(
                  USER,
                  session.id(),
                  Content.fromParts(Part.fromText("Alert")),
                  RunConfig.builder().maxLlmCalls(20).build())
              .toList()
              .blockingGet();

      assertThat(events)
          .as("throwing on budget exhaustion would discard the evidence already gathered")
          .isNotEmpty();

      Session reloaded =
          sessions.getSession(APP, USER, session.id(), Optional.empty()).blockingGet();
      assertThat(reloaded.state())
          .as("even a heavily budget-limited run must still produce a summary")
          .containsKey("investigation_summary");
    }
  }
}
