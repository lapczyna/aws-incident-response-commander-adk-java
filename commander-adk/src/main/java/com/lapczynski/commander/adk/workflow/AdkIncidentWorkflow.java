package com.lapczynski.commander.adk.workflow;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.adk.agent.DiagnosisAgents;
import com.lapczynski.commander.adk.agent.IncidentAgentFactory;
import com.lapczynski.commander.adk.agent.PolicyGateAgent;
import com.lapczynski.commander.adk.agent.RecoveryVerifierAgent;
import com.lapczynski.commander.adk.agent.ReportAgents;
import com.lapczynski.commander.adk.approval.ApprovalResumption;
import com.lapczynski.commander.adk.diagnosis.HypothesisParser;
import com.lapczynski.commander.adk.diagnosis.RemediationProposalParser;
import com.lapczynski.commander.application.port.AlertSignal;
import com.lapczynski.commander.application.port.IncidentWorkflow;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent runtime, behind the application's port.
 *
 * <p>Everything ADK-shaped is on this side of the boundary: runners, sessions, events, the
 * confirmation protocol. What crosses back is a {@link WorkflowResult}, which is four records and
 * an enum's worth of vocabulary. That is what lets the incident state machine be tested without
 * starting an agent, and what keeps {@code ArchitectureRulesTest} able to say that ADK types never
 * leave this module.
 *
 * <p><strong>The session is keyed by the incident id.</strong> Not by a request, a user, or
 * anything held in memory — so a process that dies while an incident waits for a human comes back,
 * finds the row, and resumes the same invocation. The two runners share one session service for the
 * same reason: the narrator has to see what the investigation found, and a second session would
 * hide it.
 *
 * <p>Nothing here decides anything. The gate decides whether a proposal may proceed, the tool
 * decides again at execution time, and the verifier decides by arithmetic. This class reads what
 * they decided out of the event stream and reports it.
 */
public class AdkIncidentWorkflow implements IncidentWorkflow {

  private static final Logger log = LoggerFactory.getLogger(AdkIncidentWorkflow.class);

  /**
   * The ADK user the sessions belong to.
   *
   * <p>Constant, because ADK's user scoping is not this system's authorisation model. Who may
   * approve what is decided by {@code ApprovalService} against a domain {@code Actor}; putting a
   * human's identity here as well would create a second, weaker answer to the same question.
   */
  private static final String SESSION_USER = "incident-commander";

  /** Session-state keys seeded before a run. */
  static final String KEY_INCIDENT_VERSION = "incident_version";

  static final String KEY_CITABLE_EVIDENCE = "citable_evidence";

  private final Runner diagnosis;
  private final Runner closure;
  private final BaseSessionService sessions;
  private final EvidenceCapture evidenceCapture;
  private final IncidentReportService reports;

  public AdkIncidentWorkflow(
      Runner diagnosis,
      Runner closure,
      BaseSessionService sessions,
      EvidenceCapture evidenceCapture,
      IncidentReportService reports) {
    this.diagnosis = diagnosis;
    this.closure = closure;
    this.sessions = sessions;
    this.evidenceCapture = evidenceCapture;
    this.reports = reports;
  }

  // -------------------------------------------------------------------------------------------
  // Investigate
  // -------------------------------------------------------------------------------------------

  @Override
  public WorkflowResult investigate(Incident incident, AlertSignal alert, long approvalVersion) {
    String sessionId = incident.id().toString();
    createSession(sessionId, seedState(incident, alert, approvalVersion));

    List<Event> events =
        run(diagnosis, sessionId, Content.fromParts(Part.fromText(alert.asAlertText())));

    // Persisted before the result is interpreted, so that an incident which ends up refused still
    // has the observations that justified the refusal. Evidence is the unit of accountability;
    // discarding it because the conclusion was uncomfortable would defeat the point.
    evidenceCapture.persist(incident.id(), events);

    Map<String, Object> state = accumulateState(events);
    String decision = string(state.get(PolicyGateAgent.KEY_DECISION));

    // What the run actually produced, at INFO. An investigation that ends early is diagnosed by
    // which stage last wrote something, and reconstructing that from a stack trace means reading
    // ADK's internals rather than this pipeline's.
    log.info(
        "Investigation finished: incidentId={} events={} stateKeys={} decision={}",
        incident.id(),
        events.size(),
        state.keySet(),
        decision.isEmpty() ? "none" : decision);

    if ("DENIED".equals(decision)) {
      return new WorkflowResult.Refused(gateExplanation(events));
    }
    if (!"ALLOWED".equals(decision)) {
      // The gate never ran, which means the pipeline stopped earlier: a budget, a deadline, or a
      // model that failed. Not a refusal — nothing refused anything.
      return new WorkflowResult.Failed(
          "The investigation ended before policy was consulted. "
              + summaryOr(state, "No conclusion was recorded."));
    }

    Optional<String> confirmationCallId =
        events.stream()
            .map(ApprovalResumption::confirmationCallId)
            .flatMap(Optional::stream)
            .findFirst();

    if (confirmationCallId.isEmpty()) {
      // Allowed, but the executor never asked for confirmation. Treating this as "go ahead" would
      // be the worst possible reading; it is reported as nothing having been proposed.
      log.warn(
          "Policy allowed an action but no confirmation was requested: incidentId={}",
          incident.id());
      return new WorkflowResult.NoActionProposed(
          summaryOr(state, "A remediation was allowed but never submitted for approval."));
    }

    Optional<ProposedAction> proposal =
        RemediationProposalParser.parse(string(state.get(PolicyGateAgent.KEY_PROPOSAL)));
    if (proposal.isEmpty()) {
      // Unreachable in practice — the gate refuses an unparseable proposal before allowing it —
      // but an approval request with no action is not something to improvise around.
      return new WorkflowResult.Failed(
          "The proposal became unparseable between the gate allowing it and the approval being "
              + "raised. No approval was requested.");
    }

    ProposedAction action = proposal.get();
    return new WorkflowResult.AwaitingApproval(
        action,
        risk(state, action),
        confidence(state),
        summaryOr(state, "No rationale was recorded."),
        action.humanDescription(),
        evidenceCapture.citedEvidence(incident.id()),
        confirmationCallId.get());
  }

  // -------------------------------------------------------------------------------------------
  // Resume
  // -------------------------------------------------------------------------------------------

  @Override
  public WorkflowResult resume(Incident incident, String confirmationCallId, boolean approved) {
    String sessionId = incident.id().toString();

    List<Event> events =
        run(
            diagnosis,
            sessionId,
            approved
                ? ApprovalResumption.confirm(confirmationCallId)
                : ApprovalResumption.decline(confirmationCallId));

    if (!approved) {
      return new WorkflowResult.Executed(
          "The decision was declined and nothing was executed.", true);
    }

    Optional<Map<String, Object>> execution = toolResponse(events, "executeRemediation");
    if (execution.isEmpty()) {
      return new WorkflowResult.Failed(
          "The approved action was resumed but the executor produced no result. Nothing can be "
              + "said about whether anything changed, so the incident is failed rather than "
              + "reported as done.");
    }

    Map<String, Object> result = execution.get();
    String status = string(result.get("status"));

    return switch (status) {
      case "executed" ->
          new WorkflowResult.Executed(
              string(result.get("detail")), Boolean.TRUE.equals(result.get("dryRun")));

      // The fingerprint was already claimed. Reported as an execution, because it is: the action
      // happened, once, and this is the replay being refused rather than a failure.
      case "already_executed" ->
          new WorkflowResult.Executed(
              "This action had already been performed; the recorded result was returned and "
                  + "nothing was done a second time.",
              false);

      case "refused" -> new WorkflowResult.Refused(string(result.get("explanation")));

      default ->
          new WorkflowResult.Failed("The executor returned an unrecognised status: " + status);
    };
  }

  // -------------------------------------------------------------------------------------------
  // Verify
  // -------------------------------------------------------------------------------------------

  @Override
  public WorkflowResult verify(Incident incident, AlertSignal alert) {
    String sessionId = incident.id().toString();

    // The evidence catalogue is numbered by the same code that numbers it in the report, so a
    // citation the narrator writes resolves to the row a reader will see. Supplying the catalogue
    // rather than letting the model recall what it saw is what makes citations checkable.
    Map<String, Object> state =
        Map.of(KEY_CITABLE_EVIDENCE, reports.citableEvidence(incident.id()));

    List<Event> events =
        run(
            closure,
            sessionId,
            Content.fromParts(Part.fromText("Measure recovery and write the narrative.")),
            state);

    Map<String, Object> accumulated = accumulateState(events);
    String outcome = string(accumulated.get(RecoveryVerifierAgent.KEY_OUTCOME));

    if (outcome.isEmpty()) {
      return new WorkflowResult.Failed(
          "Recovery was never measured, so there is no verdict. The incident is not resolved.");
    }

    return new WorkflowResult.Verified(
        outcome,
        Boolean.TRUE.equals(accumulated.get(RecoveryVerifierAgent.KEY_RESOLVED)),
        string(accumulated.get(RecoveryVerifierAgent.KEY_SUMMARY)),
        string(accumulated.get(ReportAgents.KEY_NARRATIVE)));
  }

  // -------------------------------------------------------------------------------------------
  // Session and event plumbing
  // -------------------------------------------------------------------------------------------

  /**
   * Everything the pipeline needs to know that a model must not be trusted to supply.
   *
   * <p>The verification criteria in particular: the metric, the value that motivated the incident,
   * and the threshold recovery is measured against are captured from the alert and seeded here,
   * before any model has seen anything. Letting the investigation choose them later would let the
   * thing being judged pick its own bar (ADR-0010).
   */
  private Map<String, Object> seedState(
      Incident incident, AlertSignal alert, long approvalVersion) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(RecoveryVerifierAgent.KEY_INCIDENT_ID, incident.id().toString());
    state.put(RecoveryVerifierAgent.KEY_SERVICE, incident.affectedService().name());
    state.put(KEY_INCIDENT_VERSION, approvalVersion);

    if (alert.isMeasurable()) {
      state.put(RecoveryVerifierAgent.KEY_METRIC, alert.metricName());
      state.put(RecoveryVerifierAgent.KEY_BEFORE, alert.observedValue());
      state.put(RecoveryVerifierAgent.KEY_THRESHOLD, alert.recoveryThreshold());
    }
    return state;
  }

  private void createSession(String sessionId, Map<String, Object> state) {
    Session existing =
        sessions
            .getSession(IncidentAgentFactory.APP_NAME, SESSION_USER, sessionId, Optional.empty())
            .blockingGet();

    if (existing == null) {
      sessions
          .createSession(IncidentAgentFactory.APP_NAME, SESSION_USER, state, sessionId)
          .blockingGet();
    }
  }

  private List<Event> run(Runner runner, String sessionId, Content message) {
    return run(runner, sessionId, message, Map.of());
  }

  private List<Event> run(
      Runner runner, String sessionId, Content message, Map<String, Object> stateDelta) {
    return runner
        .runAsync(
            SESSION_USER,
            sessionId,
            message,
            RunConfig.builder()
                .maxLlmCalls(IncidentAgentFactory.MAX_LLM_CALLS_PER_INVESTIGATION)
                .build(),
            stateDelta)
        .toList()
        .blockingGet();
  }

  /**
   * Replays the state deltas in order.
   *
   * <p>Reading the session back would be one call rather than a fold, and would also return state
   * written by anything else that touched the session in between. The deltas from this run are
   * exactly what this run concluded.
   */
  private static Map<String, Object> accumulateState(List<Event> events) {
    Map<String, Object> state = new LinkedHashMap<>();
    for (Event event : events) {
      state.putAll(event.actions().stateDelta());
    }
    return state;
  }

  /** The gate's own words, which carry the violated rules the state delta only names. */
  private static String gateExplanation(List<Event> events) {
    return events.stream()
        .filter(event -> "policy_gate".equals(event.author()))
        .map(AdkIncidentWorkflow::text)
        .filter(text -> !text.isBlank())
        .reduce((first, second) -> second)
        .orElse("The policy gate refused the proposed action.");
  }

  private static Optional<Map<String, Object>> toolResponse(List<Event> events, String toolName) {
    return events.stream()
        .map(Event::content)
        .flatMap(Optional::stream)
        .map(Content::parts)
        .flatMap(Optional::stream)
        .flatMap(List::stream)
        .map(Part::functionResponse)
        .flatMap(Optional::stream)
        .filter(response -> toolName.equals(response.name().orElse("")))
        .map(response -> response.response().orElse(Map.of()))
        .reduce((first, second) -> second);
  }

  private static String text(Event event) {
    return event
        .content()
        .flatMap(Content::parts)
        .map(
            parts ->
                parts.stream()
                    .map(part -> part.text().orElse(""))
                    .filter(value -> !value.isBlank())
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b))
        .orElse("");
  }

  private static String summaryOr(Map<String, Object> state, String fallback) {
    String hypothesis = string(state.get(DiagnosisAgents.KEY_HYPOTHESIS));
    if (!hypothesis.isBlank()) {
      return hypothesis;
    }
    String summary = string(state.get(IncidentAgentFactory.KEY_INVESTIGATION_SUMMARY));
    return summary.isBlank() ? fallback : summary;
  }

  private static RiskLevel risk(Map<String, Object> state, ProposedAction action) {
    String assessed = string(state.get(PolicyGateAgent.KEY_ASSESSED_RISK));
    try {
      return assessed.isBlank() ? action.type().baselineRisk() : RiskLevel.valueOf(assessed);
    } catch (IllegalArgumentException e) {
      // An unrecognised risk name falls back to the action's floor rather than to the lowest
      // value. A parsing slip must not be able to make an action look safer than it is.
      return action.type().baselineRisk();
    }
  }

  private static Confidence confidence(Map<String, Object> state) {
    Object raw = state.get(PolicyGateAgent.KEY_CONFIDENCE);
    if (raw instanceof Number number) {
      return Confidence.clamped(number.doubleValue());
    }
    try {
      return Confidence.clamped(Double.parseDouble(string(raw)));
    } catch (NumberFormatException e) {
      return HypothesisParser.UNCITED_CONFIDENCE_CAP;
    }
  }

  private static String string(Object value) {
    return value == null ? "" : value.toString();
  }

  /** Exposed so the application can list what an investigation observed. */
  public List<EvidenceId> citedEvidence(Incident incident) {
    return evidenceCapture.citedEvidence(incident.id());
  }
}
