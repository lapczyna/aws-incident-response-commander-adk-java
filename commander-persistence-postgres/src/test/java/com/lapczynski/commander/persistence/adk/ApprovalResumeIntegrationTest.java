package com.lapczynski.commander.persistence.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.approval.ApprovalResumption;
import com.lapczynski.commander.adk.approval.RemediationTool;
import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.incident.ServiceRef;
import com.lapczynski.commander.domain.incident.Severity;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.persistence.PostgresIntegrationTest;
import com.lapczynski.commander.testing.FakeLlm;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The headline capability: an investigation pauses for a human, the process dies, a new process
 * starts, the approval arrives, and the action executes exactly once.
 *
 * <p>Every piece of this is real. A real ADK {@code LongRunningFunctionTool} with {@code
 * requireConfirmation}, real HITL pause and resume, a real PostgreSQL session service, and a
 * genuinely discarded {@code Runner}. The only thing faked is the model.
 *
 * <p>This test is the reason {@code PostgresSessionService} (ADR-0004) and the deprecated {@code
 * ResumabilityConfig} (ADR-0005) exist. If it passes, both decisions were worth their cost.
 */
class ApprovalResumeIntegrationTest extends PostgresIntegrationTest {

  private static final String APP = "incident_commander";
  private static final String USER = "operator";
  private static final String ALLOWED_ARN =
      "arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout";
  private static final Map<String, String> VALID_TAGS =
      Map.of("Project", "aws-incident-response-commander");

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private IdempotencyStore idempotency;
  @Autowired private com.lapczynski.commander.application.port.IncidentRepository incidents;

  /** Counts real executions, so "exactly once" is observed rather than inferred. */
  private AtomicInteger executions;

  private IncidentId incidentId;

  @BeforeEach
  void setUp() {
    truncateIncidents(jdbc);
    jdbc.sql("DELETE FROM adk_sessions").update();
    executions = new AtomicInteger();

    Incident incident =
        Incident.open(
            IncidentId.newId(),
            "Latency after deploy v2.4.0",
            new ServiceRef("checkout", "demo"),
            Severity.SEV2,
            Instant.parse("2026-09-09T12:00:00Z"));
    incidents.create(incident, Actor.SYSTEM);
    incidentId = incident.id();
  }

  /**
   * @param dryRun whether actions are simulated.
   *     <p>This parameter is the whole reason these tests are explicit about it. In dry-run mode
   *     {@link RemediationTool} deliberately does <em>not</em> invoke the executor, so a test that
   *     counts executor calls under dry-run measures nothing and passes whatever the system does.
   *     Tests asserting that an action ran therefore use {@code dryRun = false}; nothing real
   *     happens because the executor itself is the seam, and here it only increments a counter.
   */
  private PolicyEngine engine(boolean dryRun) {
    return new PolicyEngine(
        new PolicyConfiguration(
            true,
            dryRun,
            "123456789012",
            "eu-west-1",
            "demo",
            Set.of(ActionType.ROLLBACK_DEPLOYMENT),
            Set.of(ALLOWED_ARN),
            new PolicyConfiguration.TagRequirement("Project", "aws-incident-response-commander"),
            new Confidence(0.7),
            3));
  }

  /**
   * Builds a runner over a brand-new session service.
   *
   * <p>Called twice per test with nothing shared but the database, which is what makes the second
   * call a genuine simulation of a restarted process.
   */
  private Runner freshRunner(FakeLlm model, boolean dryRun) {
    RemediationTool tool =
        new RemediationTool(
            engine(dryRun),
            idempotency,
            VALID_TAGS,
            action -> {
              executions.incrementAndGet();
              return "rolled back to checkout:41";
            });

    LlmAgent executor =
        LlmAgent.builder()
            .name("remediation_executor")
            .description("Executes an approved remediation.")
            .model(model)
            .maxSteps(3)
            .tools(
                List.of(
                    LongRunningFunctionTool.create(
                        tool, "executeRemediation", /* requireConfirmation= */ true)))
            .instruction("Execute the approved remediation using the tool.")
            .build();

    @SuppressWarnings("deprecation") // ADR-0005: the only way to enable HITL resumption in 1.9.0.
    App app =
        App.builder()
            .name(APP)
            .rootAgent(executor)
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();

    return Runner.builder()
        .app(app)
        .sessionService(new PostgresSessionService(jdbc, transactions, Schedulers.io()))
        .build();
  }

  /** A model that asks for the rollback once, then acknowledges whatever comes back. */
  private FakeLlm rollbackModel() {
    return FakeLlm.builder()
        .callsTool(
            "executeRemediation",
            Map.ofEntries(
                Map.entry("incidentId", incidentId.toString()),
                Map.entry("incidentVersion", 0),
                Map.entry("actionType", "ROLLBACK_DEPLOYMENT"),
                Map.entry("targetArn", ALLOWED_ARN),
                Map.entry("targetAccountId", "123456789012"),
                Map.entry("targetRegion", "eu-west-1"),
                Map.entry("targetEnvironment", "demo"),
                Map.entry("confidence", 0.9),
                Map.entry("humanDescription", "Roll back checkout to task definition 41")))
        .fallback("Remediation step complete.")
        .build();
  }

  @Test
  @DisplayName("the invocation pauses for approval, survives a restart, then executes exactly once")
  void pausesRestartsResumesAndExecutesOnce() {
    String sessionId = "approval-resume-session";

    // ---- Process 1: run until the tool asks for confirmation, then "die" -------------------
    Runner first = freshRunner(rollbackModel(), false);
    first.sessionService().createSession(APP, USER, Map.of(), sessionId).blockingGet();

    List<Event> beforeApproval =
        first
            .runAsync(
                USER,
                sessionId,
                Content.fromParts(Part.fromText("Execute the approved rollback.")),
                RunConfig.builder().maxLlmCalls(10).build())
            .toList()
            .blockingGet();

    Optional<String> confirmationCallId =
        beforeApproval.stream()
            .map(ApprovalResumption::confirmationCallId)
            .flatMap(Optional::stream)
            .findFirst();

    assertThat(confirmationCallId)
        .as("ADK must pause and ask for confirmation before the tool body runs")
        .isPresent();
    assertThat(executions.get()).as("nothing may execute before a human has approved it").isZero();

    // ---- The process dies here. Only the database survives. --------------------------------

    // ---- Process 2: a brand-new runner and session service ---------------------------------
    Runner afterRestart = freshRunner(rollbackModel(), false);

    Session recovered =
        afterRestart
            .sessionService()
            .getSession(APP, USER, sessionId, Optional.empty())
            .blockingGet();

    assertThat(recovered)
        .as("the restarted process reconstructs the paused invocation from PostgreSQL alone")
        .isNotNull();
    assertThat(recovered.events())
        .as("the persisted events are what ADK replays to resume")
        .isNotEmpty();

    List<Event> afterApproval =
        afterRestart
            .runAsync(
                USER,
                sessionId,
                ApprovalResumption.confirm(confirmationCallId.orElseThrow()),
                RunConfig.builder().maxLlmCalls(10).build())
            .toList()
            .blockingGet();

    assertThat(afterApproval).isNotEmpty();
    assertThat(executions.get())
        .as("the approved action runs exactly once, in a process that never saw the request")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the paused invocation is fully persisted, with every event resumption needs")
  void pausedInvocationIsFullyPersisted() {
    String sessionId = "persistence-shape-session";

    Runner runner = freshRunner(rollbackModel(), false);
    runner.sessionService().createSession(APP, USER, Map.of(), sessionId).blockingGet();
    runner
        .runAsync(
            USER,
            sessionId,
            Content.fromParts(Part.fromText("Execute the approved rollback.")),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    // Reloaded through a fresh service, exactly as a restarted process would see it.
    Session reloaded =
        new PostgresSessionService(jdbc, transactions, Schedulers.io())
            .getSession(APP, USER, sessionId, Optional.empty())
            .blockingGet();

    List<Event> events = reloaded.events();

    assertThat(events).as("the paused invocation must leave a trail").isNotEmpty();

    assertThat(events)
        .as("the original tool call the agent emitted")
        .anySatisfy(
            event ->
                assertThat(event.functionCalls())
                    .anySatisfy(
                        call -> {
                          assertThat(call.name()).contains("executeRemediation");
                          assertThat(call.id()).as("resumption matches on this id").isPresent();
                        }));

    assertThat(events)
        .as(
            "the event recording that confirmation was requested; confirmationRequestedIds() is "
                + "built from it")
        .anySatisfy(event -> assertThat(event.actions().requestedToolConfirmations()).isNotEmpty());

    assertThat(events)
        .as("the adk_request_confirmation call the approval will answer")
        .anySatisfy(event -> assertThat(ApprovalResumption.confirmationCallId(event)).isPresent());

    assertThat(events)
        .as("resumption only honours calls this agent emitted, so the author must survive")
        .anyMatch(event -> "remediation_executor".equals(event.author()));
  }

  @Test
  @DisplayName("the confirmation message itself is appended to the session")
  void confirmationMessageIsAppended() {
    String sessionId = "confirmation-append-session";

    Runner runner = freshRunner(rollbackModel(), false);
    runner.sessionService().createSession(APP, USER, Map.of(), sessionId).blockingGet();
    List<Event> paused =
        runner
            .runAsync(
                USER,
                sessionId,
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
            sessionId,
            ApprovalResumption.confirm(callId),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    Session reloaded =
        new PostgresSessionService(jdbc, transactions, Schedulers.io())
            .getSession(APP, USER, sessionId, Optional.empty())
            .blockingGet();

    assertThat(reloaded.events())
        .as(
            "the processor scans session events for this response; if it is not here, no approval "
                + "can ever be matched")
        .anySatisfy(
            event ->
                assertThat(event.functionResponses())
                    .anySatisfy(
                        response ->
                            assertThat(response.name()).contains("adk_request_confirmation")));
  }

  @Test
  @DisplayName("a replayed approval does not execute the action a second time")
  void replayedApprovalDoesNotExecuteTwice() {
    String sessionId = "replay-session";

    Runner runner = freshRunner(rollbackModel(), false);
    runner.sessionService().createSession(APP, USER, Map.of(), sessionId).blockingGet();

    List<Event> paused =
        runner
            .runAsync(
                USER,
                sessionId,
                Content.fromParts(Part.fromText("Execute the approved rollback.")),
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
            sessionId,
            ApprovalResumption.confirm(callId),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    assertThat(executions.get()).isEqualTo(1);

    // The same approval, replayed. In the real system this is a resubmitted request or a
    // duplicated message; here it is the same confirmation sent twice.
    runner
        .runAsync(
            USER,
            sessionId,
            ApprovalResumption.confirm(callId),
            RunConfig.builder().maxLlmCalls(10).build())
        .toList()
        .blockingGet();

    assertThat(executions.get())
        .as(
            "the idempotency claim is what stops a replay, and it is held in the database rather "
                + "than in this process")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("policy is re-checked at execution time, so an approval cannot outlive its basis")
  void policyIsRecheckedAfterApproval() {
    // A tool wired to a policy engine that permits nothing, standing for conditions that changed
    // between the human approving and the action running.
    RemediationTool refusing =
        new RemediationTool(
            new PolicyEngine(PolicyConfiguration.safeDefaults("123456789012", "eu-west-1", "demo")),
            idempotency,
            VALID_TAGS,
            action -> {
              executions.incrementAndGet();
              return "should never happen";
            });

    Map<String, Object> result =
        refusing.executeRemediation(
            incidentId.toString(),
            0,
            "ROLLBACK_DEPLOYMENT",
            ALLOWED_ARN,
            "123456789012",
            "eu-west-1",
            "demo",
            0.95,
            "Roll back checkout");

    assertThat(result)
        .as("approval is necessary and never sufficient; the engine runs again here")
        .containsEntry("status", "refused")
        .containsEntry("reason", "POLICY_DENIED_AT_EXECUTION");
    assertThat(executions.get()).isZero();
  }

  @Test
  @DisplayName("a dry run reports itself as a dry run")
  void dryRunIsReportedHonestly() {
    RemediationTool tool =
        new RemediationTool(
            engine(true),
            idempotency,
            VALID_TAGS,
            action -> {
              executions.incrementAndGet();
              return "real execution";
            });

    Map<String, Object> result =
        tool.executeRemediation(
            incidentId.toString(),
            0,
            "ROLLBACK_DEPLOYMENT",
            ALLOWED_ARN,
            "123456789012",
            "eu-west-1",
            "demo",
            0.95,
            "Roll back checkout");

    assertThat(result).containsEntry("status", "executed").containsEntry("dryRun", true);
    assertThat(result.get("detail").toString())
        .as("a report must never imply a real change was made when it was not")
        .contains("DRY RUN");
    assertThat(executions.get())
        .as(
            "dry run must not reach the executor at all; this is the assertion whose absence "
                + "made an earlier version of these tests measure nothing")
        .isZero();
  }
}
