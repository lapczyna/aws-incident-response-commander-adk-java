package com.lapczynski.commander.adk.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.diagnosis.HypothesisParser;
import com.lapczynski.commander.adk.diagnosis.RemediationProposalParser;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.incident.IncidentStatus;
import com.lapczynski.commander.domain.policy.PolicyDecision;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stage that decides whether a proposed action may proceed.
 *
 * <p><strong>There is no model here.</strong> No prompt, no instruction, no natural language, and
 * nothing an attacker or a confused agent can talk to. It reads the proposal the planner produced,
 * hands it to {@link PolicyEngine}, and emits the verdict. This is the concrete form of the
 * project's governing constraint, and it is a {@link BaseAgent} rather than an {@code LlmAgent} for
 * exactly that reason (ADR-0003).
 *
 * <p><strong>The guarded stages are its sub-agents, not its siblings.</strong> That is deliberate,
 * and it was arrived at by finding the alternative does not work. Emitting an event with {@code
 * EventActions.endInvocation} looks like the way to stop a pipeline, but {@code SequentialAgent}
 * never consults it between sub-agents, and {@code InvocationContext.setEndInvocation} only affects
 * the child context the gate was given — each sibling builds its own child from the unchanged
 * parent. A denial expressed that way is silently ignored, and the executor runs anyway.
 *
 * <p>So the executor is not placed after the gate; it is placed <em>inside</em> it. "Nothing runs
 * unless policy allowed it" is then a property of the object graph rather than of a signal that has
 * to be honoured by code this project does not own.
 *
 * <p>This is only the <em>first</em> of two evaluations. The action tool re-runs the same engine
 * immediately before executing, because the gate's verdict can go stale between approval and
 * execution. See ADR-0007.
 */
public final class PolicyGateAgent extends BaseAgent {

  private static final Logger log = LoggerFactory.getLogger(PolicyGateAgent.class);

  /** Session-state keys this stage writes. */
  public static final String KEY_DECISION = "policy_decision";

  public static final String KEY_APPROVAL_REQUIRED = "approval_required";
  public static final String KEY_ASSESSED_RISK = "assessed_risk";

  private final PolicyEngine policyEngine;
  private final Map<String, String> targetTags;

  /**
   * @param targetTags tags read from the target resource. Supplied by the caller from AWS or the
   *     simulator, never from the model: a model that could assert its own target's tags could
   *     assert its way past the tag requirement.
   * @param guarded the stages that may run only if policy allows. They are sub-agents so that the
   *     guarantee is structural; see the class comment for why a signal-based denial does not hold.
   */
  public PolicyGateAgent(
      PolicyEngine policyEngine, Map<String, String> targetTags, List<BaseAgent> guarded) {
    super(
        "policy_gate",
        "Deterministically decides whether a proposed remediation may proceed. Contains no model.",
        guarded,
        List.of(),
        List.of());
    this.policyEngine = policyEngine;
    this.targetTags = Map.copyOf(targetTags);
  }

  /** A gate guarding nothing, for evaluating a proposal without a stage to run afterwards. */
  public PolicyGateAgent(PolicyEngine policyEngine, Map<String, String> targetTags) {
    this(policyEngine, targetTags, List.of());
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.fromCallable(() -> evaluate(invocationContext))
        .concatMap(
            decision ->
                Flowable.just(decision)
                    .concatWith(
                        isAllowed(decision)
                            ? Flowable.fromIterable(subAgents())
                                .concatMap(guarded -> guarded.runAsync(invocationContext))
                            // A denial simply does not run what it guards. Nothing to suppress,
                            // nothing to signal, nothing that could be missed downstream.
                            : Flowable.empty()));
  }

  private static boolean isAllowed(Event decision) {
    Object value = decision.actions().stateDelta().get(KEY_DECISION);
    return "ALLOWED".equals(value);
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    // Identical: the decision has no streaming component, and a live variant that behaved
    // differently would be a second code path for the most safety-critical stage in the system.
    return runAsyncImpl(invocationContext);
  }

  private Event evaluate(InvocationContext context) {
    Map<String, Object> state = context.session().state();

    Optional<ProposedAction> proposal =
        RemediationProposalParser.parse(asString(state.get(KEY_PROPOSAL)));

    if (proposal.isEmpty()) {
      // No parseable proposal is a stop, not a pass. An unparseable proposal that fell through to
      // the executor would be the worst possible outcome of a formatting error.
      return denial(
          context,
          "No parseable remediation proposal was produced, so there is nothing to authorise. "
              + "The investigation is recorded; no action will be taken.",
          List.of("NO_PROPOSAL"));
    }

    Confidence confidence = readConfidence(state);
    ProposedAction action = proposal.get();

    PolicyDecision decision =
        policyEngine.evaluate(action, IncidentStatus.AWAITING_APPROVAL, confidence, targetTags);

    if (decision instanceof PolicyDecision.Denied denied) {
      List<String> rules = denied.violations().stream().map(v -> v.rule().name()).toList();
      log.warn(
          "Policy gate DENIED: action={} target={} rules={}",
          action.type(),
          action.target().arn(),
          rules);
      return denial(context, denied.explanation(), rules);
    }

    PolicyDecision.Allowed allowed = (PolicyDecision.Allowed) decision;
    log.info(
        "Policy gate ALLOWED: action={} target={} risk={} approvalRequired={}",
        action.type(),
        action.target().arn(),
        allowed.assessedRisk(),
        allowed.approvalRequired());

    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put(KEY_DECISION, "ALLOWED");
    delta.put(KEY_ASSESSED_RISK, allowed.assessedRisk().name());
    delta.put(KEY_APPROVAL_REQUIRED, allowed.approvalRequired());

    return event(
        context,
        "Policy evaluation passed. %s".formatted(allowed.explanation()),
        EventActions.builder().stateDelta(delta).build());
  }

  /**
   * Builds a denial that also stops the pipeline.
   *
   * <p>{@code endInvocation} rather than {@code escalate}: escalate ends the enclosing loop, which
   * would leave a {@link com.google.adk.agents.SequentialAgent} free to run the executor stage
   * anyway. The distinction is easy to get wrong and would be invisible until something executed
   * after a denial.
   */
  private Event denial(InvocationContext context, String explanation, List<String> rules) {
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put(KEY_DECISION, "DENIED");
    delta.put(KEY_APPROVAL_REQUIRED, false);
    delta.put("policy_violations", rules);

    return event(
        context,
        "Policy evaluation refused this action. %s Violated rules: %s"
            .formatted(explanation, rules),
        EventActions.builder().stateDelta(delta).endInvocation(true).build());
  }

  private Event event(InvocationContext context, String text, EventActions actions) {
    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(context.invocationId())
        .author(name())
        .branch(context.branch().orElse(null))
        .content(Content.fromParts(Part.fromText(text)))
        .actions(actions)
        .timestamp(Instant.now().toEpochMilli())
        .build();
  }

  /**
   * Reads the hypothesis confidence the planner acted on.
   *
   * <p>Absent or unreadable confidence is treated as the uncited floor rather than as certainty.
   * Failing open here would let a formatting problem authorise an action.
   */
  private Confidence readConfidence(Map<String, Object> state) {
    Object raw = state.get(KEY_CONFIDENCE);
    if (raw instanceof Number number) {
      return Confidence.clamped(number.doubleValue());
    }
    if (raw instanceof String text) {
      try {
        return Confidence.clamped(Double.parseDouble(text.trim()));
      } catch (NumberFormatException ignored) {
        // Falls through to the conservative default below.
      }
    }
    return HypothesisParser.UNCITED_CONFIDENCE_CAP;
  }

  private static String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  /** Session-state key holding the planner's proposal. */
  public static final String KEY_PROPOSAL = "remediation_proposal";

  /** Session-state key holding the confidence of the hypothesis that motivated the proposal. */
  public static final String KEY_CONFIDENCE = "hypothesis_confidence";

  /** Evidence keys a hypothesis may legitimately cite. */
  public static final Set<String> CITABLE_SOURCES =
      Set.of(
          SpecialistAgents.KEY_METRICS,
          SpecialistAgents.KEY_LOGS,
          SpecialistAgents.KEY_ECS,
          SpecialistAgents.KEY_CHANGES);
}
