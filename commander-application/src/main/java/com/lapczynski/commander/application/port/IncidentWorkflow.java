package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.RiskLevel;
import java.util.List;

/**
 * The agent runtime, as the application sees it.
 *
 * <p>An outbound port, so that {@code IncidentService} can drive an investigation without importing
 * a single agent framework type. That is not architectural decoration: {@code
 * ArchitectureRulesTest} fails the build if ADK types appear outside {@code commander-adk}, and the
 * application layer is where the incident state machine is enforced. Keeping the two apart means
 * the rules about what may happen to an incident are testable without starting an agent.
 *
 * <p>Three methods, because the workflow has exactly three stopping points and each corresponds to
 * a durable status: an investigation runs until it needs a human, a human answers, and recovery is
 * then measured. Java decides which of the three happens next, by reading the incident's status
 * from the database rather than by holding a conversation in memory. A process that restarts
 * between any two of them resumes from the row.
 */
public interface IncidentWorkflow {

  /**
   * Investigates, forms a hypothesis, proposes remediation and submits it to the policy gate.
   *
   * <p>Runs until the proposal reaches the guarded executor and the runtime pauses for
   * confirmation, or until it finishes without proposing anything.
   *
   * @param approvalVersion the incident version the proposal must be bound to. Supplied by the
   *     caller rather than read from {@code incident}, because the incident is still moving: it
   *     reaches {@link com.lapczynski.commander.domain.incident.IncidentStatus#AWAITING_APPROVAL}
   *     only once this returns, and the approval has to be bound to the version it will hold while
   *     it waits. The caller verifies afterwards that the incident actually arrived at that
   *     version.
   */
  WorkflowResult investigate(Incident incident, AlertSignal alert, long approvalVersion);

  /**
   * Delivers a human decision to the paused invocation.
   *
   * @param confirmationCallId the id recorded when the invocation paused
   * @param approved what the human decided. A rejection is delivered rather than dropped, so the
   *     agent records the decision in its own event trail instead of the run dangling unexplained.
   */
  WorkflowResult resume(Incident incident, String confirmationCallId, boolean approved);

  /** Measures whether the symptom went away, then narrates the outcome. */
  WorkflowResult verify(Incident incident, AlertSignal alert);

  /** What a stage produced. Exhaustive, so the caller cannot forget an outcome. */
  sealed interface WorkflowResult {

    /**
     * A proposal passed the gate and the runtime is waiting for a human.
     *
     * <p>Everything an approver needs is here, captured at the moment the pause happened. An
     * approver must never have to go back and ask the model what it meant.
     */
    record AwaitingApproval(
        ProposedAction action,
        RiskLevel risk,
        Confidence confidence,
        String rationale,
        String expectedImpact,
        List<EvidenceId> supportingEvidence,
        String confirmationCallId)
        implements WorkflowResult {}

    /**
     * The investigation completed and proposed nothing.
     *
     * <p>A success, not a failure. An investigation that concludes there is no actionable incident
     * must not be pushed towards proposing remediation it does not believe in.
     */
    record NoActionProposed(String summary) implements WorkflowResult {}

    /** The policy gate refused the proposal. Nothing downstream of the gate ran. */
    record Refused(String explanation) implements WorkflowResult {}

    /** An approved action ran, or was simulated. */
    record Executed(String detail, boolean dryRun) implements WorkflowResult {}

    /** Recovery was measured. {@code resolved} is the arithmetic verdict, not an opinion. */
    record Verified(String outcome, boolean resolved, String summary, String narrative)
        implements WorkflowResult {}

    /**
     * The stage could not complete.
     *
     * <p>A budget exceeded, a model unavailable, a malformed proposal. Distinct from {@link
     * Refused}, which is the system working correctly and saying no.
     */
    record Failed(String reason) implements WorkflowResult {}
  }
}
