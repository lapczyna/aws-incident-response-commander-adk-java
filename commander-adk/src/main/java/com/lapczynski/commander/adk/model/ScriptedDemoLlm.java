package com.lapczynski.commander.adk.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A model that calls every tool it is offered, then says something plausible and fixed.
 *
 * <p>This is what the {@code fake} profile runs, and it is why {@code docker compose up} shows the
 * whole workflow — evidence, hypothesis, proposal, policy gate, human approval, execution,
 * verification, report — with no API key, no network and no cost.
 *
 * <p><strong>It is not a model and does not pretend to be one.</strong> It reasons about nothing.
 * What it demonstrates is the machinery around a model: that the stages run in order, that the gate
 * refuses what it should, that an approval binds to a fingerprint, that recovery is measured rather
 * than asserted. Every one of those properties has to hold regardless of what the model says, which
 * is precisely why they can be demonstrated with a model that says the same thing every time.
 *
 * <p>Distinct from {@code FakeLlm} in {@code commander-testing}, which is a test double with a
 * script and a recorder. This one is unscripted and derives its behaviour from the request, so it
 * works for any agent in the pipeline without being told about it in advance.
 */
public final class ScriptedDemoLlm extends BaseLlm {

  private static final Logger log = LoggerFactory.getLogger(ScriptedDemoLlm.class);

  /** The name under which this appears in cost accounting and telemetry. */
  public static final String MODEL_NAME = "scripted-demo";

  /**
   * The hypothesis, in the shape {@code HypothesisParser} reads.
   *
   * <p>Shaped deliberately rather than generically. The parser derives the confidence the policy
   * gate compares against, and it caps that confidence by citation quality: a hypothesis citing no
   * source that actually reported is held at 0.4 however certain it claims to be. A demo model
   * emitting prose here — or emitting the proposal JSON, which is what it used to do — therefore
   * produces an investigation refused for {@code CONFIDENCE_BELOW_THRESHOLD} every single time, and
   * the approval gate this whole system exists to demonstrate is never reached.
   *
   * <p>The four cited sources are the four specialists' output keys, so the citations are real and
   * the cap does not apply. The confidence clears the shipped minimum of 0.7 and stays well below
   * certainty, because a demo claiming 1.0 would be demonstrating the wrong thing.
   */
  private static final String HYPOTHESIS =
      """
      {"statement": "The deployment of checkout:42 about 25 minutes ago introduced a latency \
      regression.",
       "reasoning": "The p99 step change begins within two minutes of revision 42 reaching 100% \
      of tasks. Error rates are unchanged, which rules out a dependency failure, and no \
      configuration change or scaling event coincides with the step.",
       "confidence": 0.82,
       "supportingEvidence": ["evidence_metrics", "evidence_logs", "evidence_ecs", \
      "evidence_changes"]}
      """;

  /**
   * The proposal emitted once every tool has been called.
   *
   * <p>Well-formed JSON naming the demo service, so the policy gate is what decides whether it may
   * proceed rather than the parser refusing it first. Whether it is <em>allowed</em> depends
   * entirely on the deployment's allowlist: with the shipped defaults nothing is allowlisted, so
   * the gate denies it, and that denial is the correct demonstration of a safe default.
   */
  private static final String PROPOSAL =
      """
      Hypothesis: the deployment 25 minutes ago introduced the latency regression. \
      Confidence: 0.82.

      {"actionType": "ROLLBACK_DEPLOYMENT",
       "target": {"arn": "arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout",
                  "accountId": "123456789012", "region": "eu-west-1",
                  "environment": "demo", "resourceType": "ecs:service"},
       "arguments": {"taskDefinition": "checkout:41"},
       "humanDescription": "Roll the checkout service back to task definition 41, the revision \
      running before the latency step began."}
      """;

  /** What the critic says when it finds nothing to object to. */
  private static final String CRITIQUE =
      """
      The hypothesis holds up. The step change in p99 is bounded to the deployment window, the \
      error rate did not move, and no other change coincides with it. The one weakness worth \
      recording: correlation with a deployment is not proof of causation by it, and only the \
      rollback will distinguish them.
      """;

  /** The reconciled account of the evidence, which is what an operator reads first. */
  private static final String SUMMARY =
      """
      Checkout p99 latency stepped from roughly 380ms to roughly 1450ms and has stayed there. The \
      step begins within two minutes of revision checkout:42 reaching all tasks.

      The four sources corroborate rather than contradict: metrics locate the step in time, the \
      change history puts the deployment at the same moment, ECS confirms the new revision is the \
      one running, and the logs show no new error class - which is itself informative, because a \
      dependency failure would have produced one.

      Most likely cause: the deployment. Confidence: high. Nothing in the evidence argues against \
      it, and no source was unavailable.
      """;

  /** The postmortem narrative, written as though for someone who slept through the incident. */
  private static final String NARRATIVE =
      """
      ### What happened

      Checkout p99 latency roughly tripled, from about 380ms to about 1450ms, and stayed there \
      until the incident was acted on. No requests failed; they were slow.

      ### Why

      Revision checkout:42 reached all tasks about two minutes before the step began. No other \
      change, scaling event or dependency incident coincides with it, and the error rate was flat \
      throughout - which rules out the usual alternative explanation.

      ### What was done about it

      A rollback to checkout:41 was proposed, allowed by policy, and put to a human. What happened \
      next, and whether the symptom actually went away, is recorded in the verification section \
      below - decided by comparing the metric against the threshold captured when the alert fired, \
      not by asking anyone whether it worked.
      """;

  /**
   * Which response each asking stage gets, keyed by a phrase from its instruction.
   *
   * <p>Keyed on the instruction because this model is handed no identity — it sees a request and
   * has to answer it, and the instruction is the only thing in that request saying what is being
   * asked for. The four phrases are mutually exclusive, so iteration order does not matter; an
   * instruction matching none of them falls through to the hypothesis.
   *
   * <p>Two of these are parsed downstream and have to be shaped: the planner's proposal and, by
   * falling through, the hypothesis. The other two are read as prose by a human, and they exist
   * because a demo whose incident summary is a JSON object is a demo that shows the machinery
   * working and the product not.
   */
  private static final Map<String, String> BY_INSTRUCTION =
      Map.of(
          "You propose a remediation for a diagnosed incident", PROPOSAL,
          "You are the critic", CRITIQUE,
          "You are the lead investigator", SUMMARY,
          "You are writing the narrative section of a postmortem", NARRATIVE);

  public ScriptedDemoLlm() {
    super(MODEL_NAME);
    log.info(
        "Model profile: fake. Responses are scripted and deterministic; nothing is sent anywhere "
            + "and nothing is reasoned about.");
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    Set<String> offered = offeredTools(request);
    Set<String> called = alreadyCalled(request);

    return offered.stream()
        .filter(tool -> !called.contains(tool))
        .findFirst()
        .map(tool -> Flowable.just(call(tool, request)))
        .orElseGet(() -> Flowable.just(text(prose(request))));
  }

  /**
   * A single-line value the asking agent's instruction states after {@code label}.
   *
   * <p>The instruction is rendered with session state already substituted, so this reads the value
   * the pipeline put there rather than a template placeholder. Empty when the label is absent,
   * which leaves the tool to refuse rather than this model guessing.
   */
  private static Optional<String> interpolated(LlmRequest request, String label) {
    return instructionOf(request)
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith(label))
        .map(line -> line.substring(label.length()).strip())
        .filter(value -> !value.isEmpty())
        .findFirst();
  }

  /**
   * The prose answer, in the shape the asking stage will try to parse.
   *
   * <p>Chosen from the system instruction rather than from an agent name, because this model is
   * handed no identity: it sees a request and has to answer it. The instruction is the only thing
   * in the request that says what is being asked for.
   *
   * <p>Everything else — the critic, the synthesiser, the narrator — gets the hypothesis, and that
   * is harmless: those stages read prose and none of them parses what it receives. The two that do
   * parse are the two distinguished here.
   */
  private static String prose(LlmRequest request) {
    String instruction = instructionOf(request);

    for (Map.Entry<String, String> candidate : BY_INSTRUCTION.entrySet()) {
      if (instruction.contains(candidate.getKey())) {
        return candidate.getValue();
      }
    }
    return HYPOTHESIS;
  }

  private static String instructionOf(LlmRequest request) {
    return String.join(System.lineSeparator(), request.getSystemInstructions());
  }

  /**
   * The tools this agent was given.
   *
   * <p>Read from the request rather than hard-coded, so that this model works for the specialists,
   * the planner and the executor without knowing which it is talking to. An agent with no tools
   * gets prose, which is the right answer for the critic and the narrator.
   */
  private static Set<String> offeredTools(LlmRequest request) {
    return request.tools().keySet();
  }

  /**
   * The tools already called in this conversation.
   *
   * <p>Derived from the transcript, because that is the only durable record: this object holds no
   * per-conversation state, so two investigations running concurrently cannot see each other's
   * progress.
   */
  private static Set<String> alreadyCalled(LlmRequest request) {
    return request.contents().stream()
        .map(Content::parts)
        .flatMap(Optional::stream)
        .flatMap(List::stream)
        .map(Part::functionCall)
        .flatMap(Optional::stream)
        .map(call -> call.name().orElse(""))
        .filter(name -> !name.isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * Arguments good enough for the tool to answer.
   *
   * <p>The service name is the demo's, and the rest are the obvious defaults. A tool that receives
   * a nonsense argument returns an evidence gap, which would make every demo investigation report
   * missing evidence and look broken.
   */
  private static LlmResponse call(String toolName, LlmRequest request) {
    Map<String, Object> args = new LinkedHashMap<>();

    switch (toolName) {
      case "executeRemediation" -> {
        // Read out of the executor's own instruction, which interpolates them from session state
        // and tells the model to pass them verbatim. A real model does exactly that; this one does
        // the same thing by reading the same text, rather than inventing a placeholder and relying
        // on the tool to reject it.
        //
        // The placeholder it used to send was a zero UUID, on the theory that the tool would
        // refuse an incident that does not exist. It does not refuse cleanly: it claims an
        // idempotency key first, and that claim has a foreign key to the incident, so the demo
        // ended every approved remediation with a constraint violation reported as
        // "the executor returned an unrecognised status: error".
        args.put("incidentId", interpolated(request, "Incident id: ").orElse(""));
        args.put(
            "incidentVersion",
            interpolated(request, "Incident version: ")
                .map(
                    value -> {
                      try {
                        return (Object) Long.parseLong(value);
                      } catch (NumberFormatException e) {
                        return (Object) 0L;
                      }
                    })
                .orElse(0L));
        args.put("actionType", "ROLLBACK_DEPLOYMENT");
        args.put("targetArn", "arn:aws:ecs:eu-west-1:123456789012:service/commander/checkout");
        args.put("targetAccountId", "123456789012");
        args.put("targetRegion", "eu-west-1");
        args.put("targetEnvironment", "demo");
        args.put("confidence", 0.82);
        args.put("humanDescription", "Roll the checkout service back to task definition 41.");
      }
      case "queryServiceMetric" -> {
        args.put("serviceName", "checkout");
        args.put("metricName", "TargetResponseTimeP99");
      }
      case "queryServiceLogs" -> {
        args.put("serviceName", "checkout");
        args.put("pattern", "error|exception|timeout|slow");
      }
      default -> args.put("serviceName", "checkout");
    }

    return LlmResponse.builder()
        .content(
            Content.builder()
                .role("model")
                .parts(List.of(Part.fromFunctionCall(toolName, args)))
                .build())
        .build();
  }

  private static LlmResponse text(String text) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
        .turnComplete(true)
        .build();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException(
        "The scripted demo model has no live connection; this workflow is request-response.");
  }
}
