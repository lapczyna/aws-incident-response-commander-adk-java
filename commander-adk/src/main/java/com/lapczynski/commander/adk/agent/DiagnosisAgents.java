package com.lapczynski.commander.adk.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.ExitLoopTool;
import com.lapczynski.commander.domain.remediation.ActionType;
import java.util.List;

/**
 * The bounded diagnosis loop: hypothesise, critique, refine.
 *
 * <p>The loop exists because a first hypothesis is usually the most obvious reading of the
 * evidence, and the most obvious reading is exactly what a coincidence looks like. A deployment
 * that happens to coincide with an unrelated dependency failure produces a confident, wrong answer
 * on the first pass every time. Asking a critic to attack that answer is what surfaces the
 * alternative.
 *
 * <p><strong>Bounded twice.</strong> {@code maxIterations} caps the loop at three passes, and the
 * critic can end it early by calling {@link ExitLoopTool}, which sets {@code escalate}. Neither
 * bound alone is enough: without the iteration cap a critic that always finds something would loop
 * until the model-call budget ran out, and without the exit tool a settled hypothesis would be
 * pointlessly re-argued twice more at full token cost.
 */
public final class DiagnosisAgents {

  /** Session-state keys the diagnosis stage writes. */
  public static final String KEY_HYPOTHESIS = "hypothesis";

  public static final String KEY_CRITIQUE = "critique";

  /**
   * Maximum refinement passes.
   *
   * <p>Three. One pass is the obvious answer; two lets the critic force a rethink; by the third the
   * loop is usually restating itself, and a fourth would spend tokens converging on wording rather
   * than on the answer.
   */
  public static final int MAX_REFINEMENT_ITERATIONS = 3;

  private DiagnosisAgents() {}

  /** Proposes a cause from the specialists' findings. */
  public static LlmAgent hypothesisAgent(BaseLlm model) {
    return LlmAgent.builder()
        .name("hypothesis_agent")
        .description("Proposes a root cause from the collected evidence.")
        .model(model)
        .maxSteps(2)
        .outputKey(KEY_HYPOTHESIS)
        .instruction(
            """
            You propose the most likely cause of an incident from evidence four specialists have \
            already gathered. You have no tools; work only from what is below.

            Evidence:
            - Metrics: {evidence_metrics}
            - Logs: {evidence_logs}
            - ECS: {evidence_ecs}
            - Changes: {evidence_changes}

            If a critique is present, it is an attack on your previous hypothesis. Address it \
            directly: either revise your hypothesis, or explain specifically why the objection does \
            not hold. Restating the previous answer without engaging with the critique is a failure.

            Previous critique, if any: {critique?}

            Respond with JSON and nothing else:

            {
              "statement": "one sentence naming the cause",
              "reasoning": "two or three sentences, referring to specific values you relied on",
              "confidence": 0.0 to 1.0,
              "supportingEvidence": ["evidence_metrics", "evidence_logs"],
              "alternativesConsidered": "what else could explain this, and why you rejected it"
            }

            Rules:
            - supportingEvidence must list ONLY the evidence keys you actually relied on, spelled \
              exactly as above. Do not cite a source that reported nothing useful, and never cite \
              one that did not report at all - citations are checked, and an invalid one lowers \
              your assessed confidence.
            - Set confidence honestly. Confidence below 0.5 is the correct answer when the evidence \
              is thin, and it is far better than a confident guess: a proposal built on an \
              overstated hypothesis is refused by the policy engine anyway.
            - If the evidence genuinely does not identify a cause, say so in the statement and set \
              confidence low. That is a valid, useful outcome.
            - If two pieces of evidence contradict each other, your statement must acknowledge the \
              contradiction rather than choose the side that reads better.
            """)
        .build();
  }

  /**
   * Attacks the current hypothesis.
   *
   * <p>Framed as an adversary rather than a reviewer on purpose. A critic asked to "review" tends
   * to agree, and an agreeable critic makes the whole loop an expensive way to restate the first
   * answer.
   */
  public static LlmAgent hypothesisCritic(BaseLlm model) {
    return LlmAgent.builder()
        .name("hypothesis_critic")
        .description("Attacks the current hypothesis, or ends the loop when it holds up.")
        .model(model)
        .maxSteps(2)
        .outputKey(KEY_CRITIQUE)
        .tools(List.of(ExitLoopTool.INSTANCE))
        .instruction(
            """
            You are the critic. Your job is to find what is wrong with the current hypothesis, not \
            to agree with it. Assume it is the obvious reading of the evidence and that the obvious \
            reading is sometimes a coincidence.

            Current hypothesis: {hypothesis?}

            Evidence:
            - Metrics: {evidence_metrics}
            - Logs: {evidence_logs}
            - ECS: {evidence_ecs}
            - Changes: {evidence_changes}

            Attack it on these specific grounds:

            1. CORRELATION MISTAKEN FOR CAUSE. Does the hypothesis rest on two things happening at \
               about the same time? A deployment coinciding with an unrelated dependency failure \
               looks identical to a deployment causing one. Is there evidence that distinguishes \
               them, and did the hypothesis use it?
            2. UNSUPPORTED CLAIMS. Does every assertion trace to a specific value in the evidence? \
               Name any that do not.
            3. IGNORED CONTRADICTIONS. Does any evidence argue against the hypothesis? Was it \
               addressed or quietly passed over?
            4. MISSING EVIDENCE TREATED AS ABSENCE. If a source was unavailable, did the hypothesis \
               proceed as though it had reported nothing of interest?
            5. OVERSTATED CONFIDENCE. Is the confidence justified by the citations actually given?

            If you find a substantive problem, state it plainly and specifically - name the claim \
            and why it does not hold. Do not soften it.

            If the hypothesis genuinely holds up, do not invent an objection to appear useful. \
            Call the exitLoop tool to end the refinement, then say briefly why it stands.
            """)
        .build();
  }

  /** Hypothesis and critic, bounded. */
  public static LoopAgent refinementLoop(BaseLlm model) {
    return LoopAgent.builder()
        .name("hypothesis_refinement")
        .description("Refines the hypothesis under critique, up to a fixed number of passes.")
        .maxIterations(MAX_REFINEMENT_ITERATIONS)
        .subAgents(hypothesisAgent(model), hypothesisCritic(model))
        .build();
  }

  /**
   * Turns a settled hypothesis into a concrete proposal, or into nothing.
   *
   * <p>Proposing is all it does. Whether the proposal may run is decided afterwards by {@link
   * PolicyGateAgent}, in code, which is why this agent can be given a frank instruction about what
   * actions exist without that becoming a capability.
   */
  public static LlmAgent remediationPlanner(BaseLlm model) {
    return LlmAgent.builder()
        .name("remediation_planner")
        .description("Proposes a concrete remediation, or proposes none.")
        .model(model)
        .maxSteps(2)
        .outputKey(PolicyGateAgent.KEY_PROPOSAL)
        .instruction(
            """
            You propose a remediation for a diagnosed incident. You cannot perform one: whatever \
            you propose is evaluated by a deterministic policy engine and then, if it is \
            state-changing, shown to a human for approval.

            Hypothesis: {hypothesis?}
            Latest critique: {critique?}

            Evidence:
            - Metrics: {evidence_metrics}
            - Logs: {evidence_logs}
            - ECS: {evidence_ecs}
            - Changes: {evidence_changes}

            These are the ONLY actions that exist. Anything else cannot be expressed:
            %s

            Respond with JSON and nothing else:

            {
              "actionType": "ROLLBACK_DEPLOYMENT",
              "target": {
                "arn": "arn:aws:ecs:...",
                "accountId": "123456789012",
                "region": "eu-west-1",
                "environment": "demo",
                "resourceType": "ecs:service"
              },
              "arguments": {"taskDefinition": "checkout:41"},
              "humanDescription": "one sentence an approver can read and understand",
              "expectedImpact": "what will change, and what the risk is if it is wrong",
              "rationale": "why this addresses the diagnosed cause"
            }

            Rules:
            - Set actionType to "NONE" when no action is warranted. That is the right answer when \
              the incident is a false alarm, when the cause is outside this system's reach, or when \
              the hypothesis is too uncertain to act on. Proposing something merely to have \
              proposed something is the worst outcome available to you.
            - The target must be a real resource named in the evidence. Do not construct an ARN \
              that no specialist reported: it will be refused, and it wastes an approver's time.
            - Choose the smallest action that addresses the cause. Prefer a rollback to a known \
              good version over a restart when a deployment is implicated, and prefer a restart \
              over scaling when the problem is not capacity.
            - humanDescription is read by a person deciding whether to approve. Write it for them, \
              not for a log.
            - If the hypothesis is low-confidence, say so in expectedImpact. The approver needs to \
              know they are authorising an action based on an uncertain diagnosis.
            """
                .formatted(actionCatalogue()))
        .build();
  }

  /**
   * The action catalogue, generated from the enum.
   *
   * <p>Generated rather than written out, so adding an {@link ActionType} cannot leave the prompt
   * describing a stale set — the failure mode where a model is never told an action exists, or is
   * told about one that has been removed.
   */
  private static String actionCatalogue() {
    StringBuilder sb = new StringBuilder();
    for (ActionType type : ActionType.values()) {
      sb.append("            - ")
          .append(type.name())
          .append(" (baseline risk ")
          .append(type.baselineRisk())
          .append(type.isStateChanging() ? ", requires approval" : ", read-only")
          .append(")\n");
    }
    sb.append("            - NONE (propose no action)");
    return sb.toString();
  }
}
