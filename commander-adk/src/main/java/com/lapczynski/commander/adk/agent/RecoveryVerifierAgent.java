package com.lapczynski.commander.adk.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.application.verification.RecoveryVerifier;
import com.lapczynski.commander.application.verification.TooSoonToVerifyException;
import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stage that decides whether the incident is over.
 *
 * <p>Like {@link PolicyGateAgent}, there is no model here. The plan named a {@code
 * recovery_verifier LlmAgent}; building it revealed that would put a model in charge of the one
 * judgement that determines whether a human stops paying attention. A model asked "did this work?"
 * while holding the transcript of its own diagnosis and its own fix is being asked to mark its own
 * homework, and it is agreeable by construction. The verdict is a numeric comparison, so it is one
 * — see ADR-0010.
 *
 * <p>What the model does afterwards is describe the result. It never produces it.
 *
 * <p>The stage writes its outcome into session state so the report narrative agent downstream can
 * read the verdict it must describe, and it emits it as text so the verdict appears in the event
 * stream a human is watching.
 */
public final class RecoveryVerifierAgent extends BaseAgent {

  private static final Logger log = LoggerFactory.getLogger(RecoveryVerifierAgent.class);

  /** Session-state key holding the verdict, as an {@code Outcome} name. */
  public static final String KEY_OUTCOME = "verification_outcome";

  /** Session-state key holding the human-readable summary of the verdict. */
  public static final String KEY_SUMMARY = "verification_summary";

  /** Session-state key holding whether the incident may be treated as resolved. */
  public static final String KEY_RESOLVED = "verification_resolved";

  /**
   * Session-state keys the caller must have populated before this stage runs.
   *
   * <p>Read from state rather than from the model's output on purpose: what to measure and what
   * counts as recovered were fixed when the incident was raised, and letting either be restated
   * later would allow the target to move to wherever the result happened to land.
   */
  public static final String KEY_METRIC = "symptom_metric";

  public static final String KEY_BEFORE = "symptom_before_value";
  public static final String KEY_THRESHOLD = "symptom_recovery_threshold";
  public static final String KEY_SERVICE = "affected_service";
  public static final String KEY_INCIDENT_ID = "incident_id";

  private final RecoveryVerifier verifier;

  public RecoveryVerifierAgent(RecoveryVerifier verifier) {
    super(
        "recovery_verifier",
        "Deterministically measures whether the symptom went away after remediation. Contains no "
            + "model: the verdict is a numeric comparison, not a judgement.",
        List.of(),
        List.of(),
        List.of());
    this.verifier = verifier;
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.fromCallable(() -> verify(invocationContext));
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return runAsyncImpl(invocationContext);
  }

  private Event verify(InvocationContext context) {
    Map<String, Object> state = context.session().state();

    RecoveryVerifier.VerificationRequest request;
    try {
      request =
          RecoveryVerifier.VerificationRequest.of(
              IncidentId.of(asString(state.get(KEY_INCIDENT_ID))),
              asString(state.get(KEY_SERVICE)),
              asString(state.get(KEY_METRIC)),
              asDouble(state.get(KEY_BEFORE)),
              asDouble(state.get(KEY_THRESHOLD)));
    } catch (RuntimeException e) {
      // Missing or malformed criteria mean recovery cannot be judged at all. Reporting that is
      // correct; inventing criteria that the current measurement happens to satisfy is not.
      log.warn("Cannot verify recovery: criteria missing or unreadable: {}", e.toString());
      return unresolved(
          context,
          RecoveryVerification.Outcome.INDETERMINATE,
          "Recovery could not be judged because the verification criteria were missing or "
              + "unreadable. The incident is not resolved.");
    }

    RecoveryVerification verification;
    try {
      verification = verifier.verify(request);
    } catch (TooSoonToVerifyException e) {
      // Explicitly not a failure. Concluding NOT_RECOVERED because we asked early would blame a
      // fix that may well have worked.
      log.info("Verification deferred until {}", e.readyAt());
      return unresolved(
          context,
          RecoveryVerification.Outcome.INDETERMINATE,
          "There is not yet enough post-remediation data to judge recovery; the observation "
              + "window completes at %s.".formatted(e.readyAt()));
    }

    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put(KEY_OUTCOME, verification.outcome().name());
    delta.put(KEY_SUMMARY, verification.summary());
    delta.put(KEY_RESOLVED, verification.permitsResolution());

    return event(
        context,
        "Recovery verification: %s. %s".formatted(verification.outcome(), verification.summary()),
        EventActions.builder().stateDelta(delta).build());
  }

  private Event unresolved(
      InvocationContext context, RecoveryVerification.Outcome outcome, String summary) {

    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put(KEY_OUTCOME, outcome.name());
    delta.put(KEY_SUMMARY, summary);
    delta.put(KEY_RESOLVED, false);

    return event(
        context,
        "Recovery verification: %s. %s".formatted(outcome, summary),
        EventActions.builder().stateDelta(delta).build());
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

  private static String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  private static double asDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    // Deliberately allowed to throw. A threshold that cannot be read is not a threshold of zero,
    // and defaulting it either way would decide the verdict by accident.
    return Double.parseDouble(String.valueOf(value).trim());
  }
}
