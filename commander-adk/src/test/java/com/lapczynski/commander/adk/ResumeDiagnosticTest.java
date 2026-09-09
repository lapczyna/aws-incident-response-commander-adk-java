package com.lapczynski.commander.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.approval.ApprovalResumption;
import com.lapczynski.commander.testing.FakeLlm;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Establishes the HITL pause/resume baseline against ADK's own in-memory session service.
 *
 * <p>Kept as a permanent test rather than deleted after debugging. It isolates the ADK protocol
 * from this project's persistence: if the durable variant of this test ever fails while this one
 * passes, the fault is in {@code PostgresSessionService}, not in how the confirmation is built.
 */
class ResumeDiagnosticTest {

  private static final String APP = "incident_commander";
  private static final String USER = "operator";

  /**
   * A confirmable tool with numeric and mixed-type arguments.
   *
   * <p>Separate from {@link Tool} because ADK's resume check compares the arguments of the call the
   * agent emitted against the copy carried inside the confirmation request, using map equality. Any
   * type drift between the two - an Integer becoming a Long, say - makes the comparison fail and
   * the approval silently unmatched. A String-only tool cannot detect that.
   */
  public static class TypedTool {
    final AtomicInteger runs = new AtomicInteger();

    @com.google.adk.tools.Annotations.Schema(description = "Performs a guarded action.")
    public Map<String, Object> typedAction(
        @com.google.adk.tools.Annotations.Schema(description = "Target.") String target,
        @com.google.adk.tools.Annotations.Schema(description = "A version number.") Integer version,
        @com.google.adk.tools.Annotations.Schema(description = "A confidence.") Double confidence) {
      runs.incrementAndGet();
      return Map.of("status", "done", "target", target, "version", version);
    }
  }

  /** A trivially simple confirmable tool, so nothing but the protocol is under test. */
  public static class Tool {
    final AtomicInteger runs = new AtomicInteger();

    @com.google.adk.tools.Annotations.Schema(description = "Performs a guarded action.")
    public Map<String, Object> guardedAction(
        @com.google.adk.tools.Annotations.Schema(description = "What to act on.") String target) {
      runs.incrementAndGet();
      return Map.of("status", "done", "target", target);
    }
  }

  /**
   * The same flow, but resumed by a <em>different</em> Runner sharing only the session service.
   *
   * <p>This is what a restarted process looks like. It is separated from the single-runner case
   * because the two exercise different things: one tests the confirmation protocol, the other tests
   * whether resumption depends on any state held inside the Runner or the agent instance.
   */
  @Test
  @DisplayName("a different Runner sharing only the session can resume the confirmation")
  void resumeFromADifferentRunner() {
    InMemorySessionService sessions = new InMemorySessionService();
    Tool firstTool = new Tool();
    Tool secondTool = new Tool();

    Runner first = runnerFor(firstTool, sessions);
    sessions.createSession(APP, USER, Map.of(), "s2").blockingGet();

    List<Event> paused =
        first
            .runAsync(
                USER,
                "s2",
                Content.fromParts(Part.fromText("go")),
                RunConfig.builder().maxLlmCalls(10).build())
            .toList()
            .blockingGet();

    String callId =
        paused.stream()
            .map(ApprovalResumption::confirmationCallId)
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow();

    // A second Runner, second agent instance, second tool instance - only the session is shared.
    Runner second = runnerFor(secondTool, sessions);
    second
        .runAsync(
            USER,
            "s2",
            ApprovalResumption.confirm(callId),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    assertThat(secondTool.runs.get())
        .as(
            "resumption must depend only on the session, or a restarted process could never "
                + "complete an approved action")
        .isEqualTo(1);
    assertThat(firstTool.runs.get()).as("the original process ran nothing").isZero();
  }

  @Test
  @DisplayName("a confirmation resumes correctly when the tool takes numeric arguments")
  void resumeWithNumericArguments() {
    TypedTool tool = new TypedTool();
    FakeLlm model =
        FakeLlm.builder()
            .callsTool(
                "typedAction", Map.of("target", "checkout", "version", 41, "confidence", 0.9))
            .fallback("done")
            .build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("executor")
            .description("Runs a guarded action.")
            .model(model)
            .maxSteps(3)
            .tools(List.of(LongRunningFunctionTool.create(tool, "typedAction", true)))
            .instruction("Use the tool.")
            .build();

    @SuppressWarnings("deprecation")
    App app =
        App.builder()
            .name(APP)
            .rootAgent(agent)
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();

    InMemorySessionService sessions = new InMemorySessionService();
    Runner runner = Runner.builder().app(app).sessionService(sessions).build();
    sessions.createSession(APP, USER, Map.of(), "s3").blockingGet();

    List<Event> paused =
        runner
            .runAsync(
                USER,
                "s3",
                Content.fromParts(Part.fromText("go")),
                RunConfig.builder().maxLlmCalls(10).build())
            .toList()
            .blockingGet();

    String callId =
        paused.stream()
            .map(ApprovalResumption::confirmationCallId)
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow();

    runner
        .runAsync(
            USER,
            "s3",
            ApprovalResumption.confirm(callId),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    assertThat(tool.runs.get())
        .as(
            "numeric arguments must survive the confirmation round trip; if they do not, the "
                + "approval is silently unmatched and the action never runs")
        .isEqualTo(1);
  }

  private Runner runnerFor(Tool tool, InMemorySessionService sessions) {
    FakeLlm model =
        FakeLlm.builder()
            .callsTool("guardedAction", Map.of("target", "checkout"))
            .fallback("done")
            .build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("executor")
            .description("Runs a guarded action.")
            .model(model)
            .maxSteps(3)
            .tools(List.of(LongRunningFunctionTool.create(tool, "guardedAction", true)))
            .instruction("Use the tool.")
            .build();

    @SuppressWarnings("deprecation")
    App app =
        App.builder()
            .name(APP)
            .rootAgent(agent)
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();

    return Runner.builder().app(app).sessionService(sessions).build();
  }

  @Test
  @DisplayName("ADK pauses for confirmation and resumes on a confirmation response")
  void pauseAndResume() {
    Tool tool = new Tool();
    FakeLlm model =
        FakeLlm.builder()
            .callsTool("guardedAction", Map.of("target", "checkout"))
            .fallback("done")
            .build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("executor")
            .description("Runs a guarded action.")
            .model(model)
            .maxSteps(3)
            .tools(List.of(LongRunningFunctionTool.create(tool, "guardedAction", true)))
            .instruction("Use the tool.")
            .build();

    @SuppressWarnings("deprecation")
    App app =
        App.builder()
            .name(APP)
            .rootAgent(agent)
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();

    InMemorySessionService sessions = new InMemorySessionService();
    Runner runner = Runner.builder().app(app).sessionService(sessions).build();
    sessions.createSession(APP, USER, Map.of(), "s1").blockingGet();

    List<Event> paused =
        runner
            .runAsync(
                USER,
                "s1",
                Content.fromParts(Part.fromText("go")),
                RunConfig.builder().maxLlmCalls(10).build())
            .toList()
            .blockingGet();

    Optional<String> callId =
        paused.stream()
            .map(ApprovalResumption::confirmationCallId)
            .flatMap(Optional::stream)
            .findFirst();

    assertThat(callId).as("ADK must request confirmation before running the tool").isPresent();
    assertThat(tool.runs.get()).as("nothing runs before approval").isZero();

    runner
        .runAsync(
            USER,
            "s1",
            ApprovalResumption.confirm(callId.orElseThrow()),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    assertThat(tool.runs.get())
        .as("confirming must resume the original call exactly once")
        .isEqualTo(1);
  }
}
