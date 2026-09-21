package com.lapczynski.commander.adk.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.lapczynski.commander.adk.diagnosis.HypothesisParser;
import com.lapczynski.commander.adk.diagnosis.HypothesisParser.ParsedHypothesis;
import io.reactivex.rxjava3.core.Flowable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the hypothesis into the number the policy gate compares against.
 *
 * <p>No model. {@link HypothesisParser} reads the refinement loop's output, checks its citations
 * against the evidence that actually reported, and derives a confidence that is already capped for
 * citation quality. This agent's whole job is to put that number into session state under {@link
 * PolicyGateAgent#KEY_CONFIDENCE}, where three separate things read it: the gate, the planner's
 * instruction, and the approval record the human eventually sees.
 *
 * <p><strong>It exists because nothing wrote that key.</strong> The gate has always read it and
 * fallen back to {@link HypothesisParser#UNCITED_CONFIDENCE_CAP} — 0.4 — when it was absent, which
 * is the correct conservative default for an unreadable hypothesis and the wrong answer for one
 * that parsed perfectly well. With the shipped minimum of 0.7 the consequence was that every
 * proposal was refused for {@code CONFIDENCE_BELOW_THRESHOLD} no matter what the evidence showed,
 * and the approval gate the rest of this system is built around could never be reached.
 *
 * <p>That was invisible to every test of the gate, because those seed the key directly to exercise
 * the threshold rule, and invisible to every test of the parser, because it was never asked. It
 * took running the assembled pipeline against a deployment that permits an action — {@code
 * ApprovalFlowTest} — to show it.
 *
 * <p>Deliberately still conservative. An unparseable hypothesis, or one citing evidence that never
 * reported, gets the same low confidence it got before: the parser caps it, and this agent writes
 * whatever the parser decided rather than second-guessing it.
 */
public final class HypothesisAppraisalAgent extends BaseAgent {

  private static final Logger log = LoggerFactory.getLogger(HypothesisAppraisalAgent.class);

  /** Session-state key holding the sources the hypothesis legitimately cited. */
  public static final String KEY_CITED_SOURCES = "hypothesis_cited_sources";

  /** Session-state key holding citations naming evidence that never reported. */
  public static final String KEY_REJECTED_CITATIONS = "hypothesis_rejected_citations";

  public HypothesisAppraisalAgent() {
    super(
        "hypothesis_appraisal",
        "Derives the hypothesis confidence the policy gate compares against. No model.",
        List.of(),
        List.of(),
        List.of());
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.fromCallable(() -> appraise(invocationContext));
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    // Identical. A live variant that scored confidence differently would be a second answer to the
    // question the gate is about to ask.
    return runAsyncImpl(invocationContext);
  }

  private Event appraise(InvocationContext context) {
    Map<String, Object> state = context.session().state();

    String hypothesis =
        Optional.ofNullable(state.get(DiagnosisAgents.KEY_HYPOTHESIS))
            .map(Object::toString)
            .orElse("");

    ParsedHypothesis parsed = HypothesisParser.parse(hypothesis, reportedSources(state));

    if (!parsed.wellFormed()) {
      log.warn(
          "Hypothesis did not parse; confidence falls to the uncited floor of {}",
          parsed.confidence().value());
    }
    if (parsed.citedMissingEvidence()) {
      log.warn(
          "Hypothesis cited evidence that did not report: {}. Confidence capped accordingly.",
          parsed.rejectedCitations());
    }

    log.info(
        "Hypothesis appraised: confidence={} citedSources={} rejectedCitations={}",
        parsed.confidence().value(),
        parsed.citedSources(),
        parsed.rejectedCitations());

    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put(PolicyGateAgent.KEY_CONFIDENCE, parsed.confidence().value());
    delta.put(KEY_CITED_SOURCES, parsed.citedSources());
    delta.put(KEY_REJECTED_CITATIONS, parsed.rejectedCitations());

    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(context.invocationId())
        .author(name())
        .branch(context.branch().orElse(null))
        .actions(EventActions.builder().stateDelta(new ConcurrentHashMap<>(delta)).build())
        .build();
  }

  /**
   * The evidence keys that actually produced a finding this run.
   *
   * <p>Read from session state rather than assumed, because a specialist whose branch failed writes
   * nothing — and a hypothesis citing that specialist is citing evidence it never saw. That is the
   * distinction the parser uses to cap confidence, and handing it the full list of specialists
   * regardless would quietly remove the check.
   */
  private static Set<String> reportedSources(Map<String, Object> state) {
    return Set.of(
            SpecialistAgents.KEY_METRICS,
            SpecialistAgents.KEY_LOGS,
            SpecialistAgents.KEY_ECS,
            SpecialistAgents.KEY_CHANGES)
        .stream()
        .filter(key -> state.get(key) != null && !state.get(key).toString().isBlank())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
